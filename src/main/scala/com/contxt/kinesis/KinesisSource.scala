package com.contxt.kinesis

import akka.stream._
import akka.stream.scaladsl._
import akka.{Done, NotUsed}
import com.amazonaws.services.kinesis.clientlibrary.interfaces.v2.{IRecordProcessor, IRecordProcessorFactory}
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.{KinesisClientLibConfiguration, Worker}
import com.typesafe.config.{Config, ConfigFactory}
import java.lang.Thread.UncaughtExceptionHandler
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, ThreadFactory}
import org.slf4j.LoggerFactory
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try
import scala.util.control.NonFatal

/** Kinesis consumer '''does not guarantee mutually exclusive processing of shards''' during failover or load-balancing.
  * See [[http://docs.aws.amazon.com/streams/latest/dev/troubleshooting-consumers.html Kinesis Troubleshooting Guide]]
  * for more details.
  *
  * - If you require mutually exclusive processing, or want to avoid spurious errors caused by concurrent processing of
  * messages with the same key by different nodes, [[https://kafka.apache.org/ check out Kafka]].
  *
  * Kinesis producer library '''does not provide message ordering guarantees''' at a reasonable throughput,
  * see [this ticket](https://github.com/awslabs/amazon-kinesis-producer/issues/23) for more details.
  *
  * - If you require efficient messages transfer while maintaining order, [[https://kafka.apache.org/ check out Kafka]].
  *
  * '''KCL license is not compatible with open source licenses!''' See
  * [this discussion](https://issues.apache.org/jira/browse/LEGAL-198) for more details.
  *
  * - If you would like to work with an open-source compatible library, [[https://kafka.apache.org/ check out Kafka]].
  */
object KinesisSource {

  /** Creates a Source backed by Kinesis Consumer Library, with materialized valued of Future[Done] which completes
    * when the stream has terminated and the Kinesis worker has fully shutdown. */
  def apply(
      kclConfig: KinesisClientLibConfiguration,
      config: Config = ConfigFactory.load()
  ): Source[KinesisRecord, Future[Done]] = {
    val shardCheckpointConfig = ShardCheckpointConfig(config)
    val consumerStats = ConsumerStats.getInstance(config)
    KinesisSource(createKclWorker, kclConfig, shardCheckpointConfig, consumerStats)
  }

  /** Creates a Source backed by Kinesis Consumer Library, with materialized valued of Future[Done] which completes
    * when the stream has terminated and the Kinesis worker has fully shutdown. */
  def apply(
      kclConfig: KinesisClientLibConfiguration,
      shardCheckpointConfig: ShardCheckpointConfig,
      consumerStats: ConsumerStats
  ): Source[KinesisRecord, Future[Done]] = {
    KinesisSource(createKclWorker, kclConfig, shardCheckpointConfig, consumerStats)
  }

  private[kinesis] def apply(
      workerFactory: (IRecordProcessorFactory, KinesisClientLibConfiguration) => ManagedWorker,
      kclConfig: KinesisClientLibConfiguration,
      shardCheckpointConfig: ShardCheckpointConfig,
      consumerStats: ConsumerStats
  ): Source[KinesisRecord, Future[Done]] = {
    require(
      kclConfig.shouldCallProcessRecordsEvenForEmptyRecordList,
      "`kclConfig.shouldCallProcessRecordsEvenForEmptyRecordList` must be set to `true`."
    )
    val kinesisAppId = KinesisAppId(
      kclConfig.getRegionName,
      kclConfig.getStreamName,
      kclConfig.getApplicationName
    )
    MergeHub
      .source[KinesisRecord](perProducerBufferSize = 1)
      .viaMat(KillSwitches.single)(Keep.both)
      .watchTermination()(Keep.both)
      .mergeMat(MaterializerAsValue.source)(Keep.both)
      .mapMaterializedValue {
        case (((mergeSink, streamKillSwitch), streamTerminationFuture), materializerFuture) =>
          materializerFuture.flatMap { implicit materializer =>
            val processorFactory = new RecordProcessorFactoryImpl(
              kinesisAppId,
              streamKillSwitch,
              streamTerminationFuture,
              mergeSink,
              shardCheckpointConfig,
              consumerStats
            )
            createAndStartKclWorker(
              workerFactory,
              processorFactory,
              kclConfig,
              streamKillSwitch,
              streamTerminationFuture
            )
          }(scala.concurrent.ExecutionContext.global)
      }
  }

  private[kinesis] def createKclWorker(
      recordProcessorFactory: IRecordProcessorFactory,
      kclConfig: KinesisClientLibConfiguration
  ): ManagedWorker = {
    new ManagedKinesisWorker(
      new Worker.Builder()
        .recordProcessorFactory(recordProcessorFactory)
        .config(kclConfig)
        .build()
    )
  }

  private def createAndStartKclWorker(
      workerFactory: (IRecordProcessorFactory, KinesisClientLibConfiguration) => ManagedWorker,
      recordProcessorFactory: IRecordProcessorFactory,
      kclConfig: KinesisClientLibConfiguration,
      streamKillSwitch: KillSwitch,
      streamTerminationFuture: Future[Done]
  ): Future[Done] = {
    implicit val blockingContext: ExecutionContext = BlockingContext.KinesisWorkersSharedContext
    val workerShutdownPromise = Promise[Done]
    Future {
      try {
        val worker = Try(workerFactory(recordProcessorFactory, kclConfig))
        streamTerminationFuture.onComplete { _ =>
          val workerShutdownFuture = Future(worker.get.shutdownAndWait()).map(_ => Done)
          workerShutdownPromise.completeWith(workerShutdownFuture)
        }
        worker.get.run() // This call hijacks the thread.
      } catch {
        case NonFatal(e) => streamKillSwitch.abort(e)
      }
      streamKillSwitch.abort(new IllegalStateException("Worker shutdown unexpectedly."))
    }
    workerShutdownPromise.future
  }
}

private[kinesis] trait ManagedWorker {
  def run(): Unit
  def shutdownAndWait(): Unit
}

private[kinesis] class ManagedKinesisWorker(private val worker: Worker) extends ManagedWorker {
  def run(): Unit = worker.run()
  def shutdownAndWait(): Unit = worker.startGracefulShutdown().get
}

private[kinesis] class RecordProcessorFactoryImpl(
    kinesisAppId: KinesisAppId,
    streamKillSwitch: KillSwitch,
    streamTerminationFuture: Future[Done],
    mergeSink: Sink[KinesisRecord, NotUsed],
    shardCheckpointConfig: ShardCheckpointConfig,
    consumerStats: ConsumerStats
)(implicit materializer: Materializer)
    extends IRecordProcessorFactory {
  override def createProcessor(): IRecordProcessor = {
    val queue = Source
      .queue[IndexedSeq[KinesisRecord]](bufferSize = 0, OverflowStrategy.backpressure)
      .mapConcat(_.toIndexedSeq)
      .to(mergeSink)
      .run()

    new RecordProcessorImpl(
      kinesisAppId,
      streamKillSwitch,
      streamTerminationFuture,
      queue,
      shardCheckpointConfig,
      consumerStats
    )
  }
}

private[kinesis] object BlockingContext {
  private val log = LoggerFactory.getLogger(getClass)
  private val threadId = new AtomicInteger(1)

  lazy val KinesisWorkersSharedContext = BlockingContext("KinesisSourceWorker")

  private def apply(name: String): ExecutionContext = {
    val threadFactory = new ThreadFactory {
      override def newThread(r: Runnable): Thread = {
        val thread = Executors.defaultThreadFactory().newThread(r)
        thread.setName(nextThreadName(name))
        thread.setUncaughtExceptionHandler(uncaughtExceptionHandler)
        thread.setDaemon(true)
        thread
      }
    }
    ExecutionContext.fromExecutor(Executors.newCachedThreadPool(threadFactory))
  }

  private def nextThreadName(prefix: String): String = {
    f"{prefix}_${threadId.getAndIncrement()}%04d"
  }

  private val uncaughtExceptionHandler = new UncaughtExceptionHandler {
    override def uncaughtException(t: Thread, e: Throwable): Unit = {
      e match {
        case NonFatal(e) =>
          log.error(s"Uncaught exception in thread `${t.getName}`.", e)

        case _ =>
          log.error(s"Fatal error in thread `${t.getName}`, exiting VM.", e)
          System.exit(-1)
      }
    }
  }
}
