/*
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.amqp.scaladsl

import java.net.ConnectException

import akka.Done
import akka.stream._
import akka.stream.alpakka.amqp._
import akka.stream.scaladsl.{GraphDSL, Keep, Merge, Sink, Source}
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.testkit.{TestPublisher, TestSubscriber}
import akka.util.ByteString
import com.rabbitmq.client.AuthenticationFailureException

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.collection.immutable

/**
 * Needs a local running AMQP server on the default port with no password.
 */
class AmqpConnectorsSpec extends AmqpSpec {

  override implicit val patienceConfig = PatienceConfig(10.seconds)

  "The AMQP Connectors" should {

    val connectionProvider = AmqpLocalConnectionProvider

    // see AmqpDocsSpec "publish and consume elements through a simple queue again in the same JVM"

    "connection should fail to wrong broker" in {
      val connectionProvider = AmqpDetailsConnectionProvider("localhost", 5673)

      val queueName = "amqp-conn-it-spec-simple-queue-" + System.currentTimeMillis()
      val queueDeclaration = QueueDeclaration(queueName)

      val amqpSink = AmqpSink.simple(
        AmqpSinkSettings(connectionProvider)
          .withRoutingKey(queueName)
          .withDeclaration(queueDeclaration)
      )

      val input = Vector("one", "two", "three", "four", "five")
      val result = Source(input).map(s => ByteString(s)).runWith(amqpSink)
      result.failed.futureValue shouldBe an[ConnectException]
    }

    "connection should fail with wrong credentials" in {
      val connectionProvider =
        AmqpDetailsConnectionProvider("invalid", 5673)
          .withHostsAndPorts(immutable.Seq("localhost" -> 5672))
          .withCredentials(AmqpCredentials("guest", "guest1"))

      val queueName = "amqp-conn-it-spec-simple-queue-" + System.currentTimeMillis()
      val queueDeclaration = QueueDeclaration(queueName)

      val amqpSink = AmqpSink.simple(
        AmqpSinkSettings(connectionProvider)
          .withRoutingKey(queueName)
          .withDeclaration(queueDeclaration)
      )

      val input = Vector("one", "two", "three", "four", "five")
      val result = Source(input).map(s => ByteString(s)).runWith(amqpSink)
      result.failed.futureValue shouldBe an[AuthenticationFailureException]
    }

    // see AmqpDocsSpec "publish via RPC and then consume through a simple queue again in the same JVM"

    "publish via RPC which expects 2 responses per message and then consume through a simple queue again in the same JVM" in {
      val queueName = "amqp-conn-it-spec-rpc-queue-" + System.currentTimeMillis()
      val queueDeclaration = QueueDeclaration(queueName)

      val amqpRpcFlow = AmqpRpcFlow.simple(
        AmqpSinkSettings(connectionProvider)
          .withRoutingKey(queueName)
          .withDeclaration(queueDeclaration),
        2
      )

      val amqpSource = AmqpSource.atMostOnceSource(
        NamedQueueSourceSettings(connectionProvider, queueName),
        bufferSize = 1
      )

      val input = Vector("one", "two", "three", "four", "five")
      val (rpcQueueF, probe) =
        Source(input).map(s => ByteString(s)).viaMat(amqpRpcFlow)(Keep.right).toMat(TestSink.probe)(Keep.both).run
      rpcQueueF.futureValue

      val amqpSink = AmqpSink.replyTo(
        AmqpReplyToSinkSettings(connectionProvider)
      )

      amqpSource
        .mapConcat { b =>
          List(
            OutgoingMessage(b.bytes.concat(ByteString("a")), false, false).withProperties(b.properties),
            OutgoingMessage(b.bytes.concat(ByteString("aa")), false, false).withProperties(b.properties)
          )
        }
        .runWith(amqpSink)

      probe
        .request(10)
        .expectNextUnorderedN(input.flatMap(s => List(ByteString(s.concat("a")), ByteString(s.concat("aa")))))
        .expectComplete()
    }

    "correctly close a AmqpRpcFlow when stream is closed without passing any elements" in {

      Source
        .empty[ByteString]
        .via(AmqpRpcFlow.simple(AmqpSinkSettings(connectionProvider)))
        .runWith(TestSink.probe)
        .ensureSubscription()
        .expectComplete()

    }

    "handle missing reply-to header correctly" in {

      val outgoingMessage = OutgoingMessage(ByteString.empty, false, false)

      Source
        .single(outgoingMessage)
        .watchTermination()(Keep.right)
        .to(AmqpSink.replyTo(AmqpReplyToSinkSettings(connectionProvider)))
        .run()
        .futureValue shouldBe Done

      val caught = intercept[RuntimeException] {
        Source
          .single(outgoingMessage)
          .toMat(AmqpSink.replyTo(AmqpReplyToSinkSettings(connectionProvider).withFailIfReplyToMissing(true)))(
            Keep.right
          )
          .run()
          .futureValue
      }

      caught.getCause.getMessage should equal("Reply-to header was not set")

    }

    "publish from one source and consume elements with multiple sinks" in {
      val queueName = "amqp-conn-it-spec-work-queues-" + System.currentTimeMillis()
      val queueDeclaration = QueueDeclaration(queueName)
      val amqpSink = AmqpSink.simple(
        AmqpSinkSettings(connectionProvider)
          .withRoutingKey(queueName)
          .withDeclaration(queueDeclaration)
      )

      val input = Vector("one", "two", "three", "four", "five")
      Source(input).map(s => ByteString(s)).runWith(amqpSink)

      val mergedSources = Source.fromGraph(GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits._
        val count = 3
        val merge = b.add(Merge[IncomingMessage](count))
        for (n <- 0 until count) {
          val source = b.add(
            AmqpSource.atMostOnceSource(
              NamedQueueSourceSettings(connectionProvider, queueName)
                .withDeclaration(queueDeclaration),
              bufferSize = 1
            )
          )
          source.out ~> merge.in(n)
        }

        SourceShape(merge.out)
      })

      val result = mergedSources.map(_.bytes.utf8String).take(input.size).runWith(Sink.seq)

      result.futureValue.sorted shouldEqual input.sorted
    }

    "not fail on a fast producer and a slow consumer" in {
      val queueName = "amqp-conn-it-spec-simple-queue-2-" + System.currentTimeMillis()
      val queueDeclaration = QueueDeclaration(queueName)
      val amqpSource = AmqpSource.atMostOnceSource(
        NamedQueueSourceSettings(connectionProvider, queueName).withDeclaration(queueDeclaration),
        bufferSize = 2
      )

      val amqpSink = AmqpSink.simple(
        AmqpSinkSettings(connectionProvider)
          .withRoutingKey(queueName)
          .withDeclaration(queueDeclaration)
      )

      val publisher = TestPublisher.probe[ByteString]()
      val subscriber = TestSubscriber.probe[IncomingMessage]()
      amqpSink.addAttributes(Attributes.inputBuffer(1, 1)).runWith(Source.fromPublisher(publisher))
      amqpSource.addAttributes(Attributes.inputBuffer(1, 1)).runWith(Sink.fromSubscriber(subscriber))

      // note that this essentially is testing rabbitmq just as much as it tests our sink and source
      publisher.ensureSubscription()
      subscriber.ensureSubscription()

      publisher.expectRequest() shouldEqual 1
      publisher.sendNext(ByteString("one"))

      publisher.expectRequest()
      publisher.sendNext(ByteString("two"))

      publisher.expectRequest()
      publisher.sendNext(ByteString("three"))

      publisher.expectRequest()
      publisher.sendNext(ByteString("four"))

      publisher.expectRequest()
      publisher.sendNext(ByteString("five"))

      subscriber.request(4)
      subscriber.expectNext().bytes.utf8String shouldEqual "one"
      subscriber.expectNext().bytes.utf8String shouldEqual "two"
      subscriber.expectNext().bytes.utf8String shouldEqual "three"
      subscriber.expectNext().bytes.utf8String shouldEqual "four"

      subscriber.request(1)
      subscriber.expectNext().bytes.utf8String shouldEqual "five"

      subscriber.cancel()
      publisher.sendComplete()
      succeed
    }

    // see AmqpDocsSpec "pub-sub from one source with multiple sinks"

    // see AmqpDocsSpec "publish and consume elements through a simple queue again in the same JVM without autoAck"

    // "republish message without autoAck if nack is sent"

    "keep connection open if downstream closes and there are pending acks" in {
      val connectionSettings = AmqpDetailsConnectionProvider("localhost", 5672)

      val queueName = "amqp-conn-it-spec-simple-queue-" + System.currentTimeMillis()
      val queueDeclaration = QueueDeclaration(queueName)

      val amqpSink = AmqpSink.simple(
        AmqpSinkSettings(connectionSettings)
          .withRoutingKey(queueName)
          .withDeclaration(queueDeclaration)
      )

      val amqpSource = AmqpSource.committableSource(
        NamedQueueSourceSettings(connectionSettings, queueName).withDeclaration(queueDeclaration),
        bufferSize = 10
      )

      val input = Vector("one", "two", "three", "four", "five")
      Source(input).map(s => ByteString(s)).runWith(amqpSink).futureValue shouldEqual Done

      val result = amqpSource
        .take(input.size)
        .runWith(Sink.seq)

      result.futureValue.map(cm => {
        noException should be thrownBy cm.ack().futureValue
      })
    }

    "not republish message without autoAck(false) if nack is sent" in {
      val queueName = "amqp-conn-it-spec-simple-queue-" + System.currentTimeMillis()
      val queueDeclaration = QueueDeclaration(queueName)

      val amqpSink = AmqpSink.simple(
        AmqpSinkSettings(connectionProvider)
          .withRoutingKey(queueName)
          .withDeclaration(queueDeclaration)
      )
      val input = Vector("one", "two", "three", "four", "five")
      Source(input).map(s => ByteString(s)).runWith(amqpSink).futureValue shouldEqual Done

      val amqpSource = AmqpSource.committableSource(
        NamedQueueSourceSettings(connectionProvider, queueName).withDeclaration(queueDeclaration),
        bufferSize = 10
      )

      val result1 = amqpSource
        .mapAsync(1)(cm => cm.nack(requeue = false).map(_ => cm))
        .take(input.size)
        .runWith(Sink.seq)

      Await.ready(result1, 3.seconds)

      val result2 = amqpSource
        .mapAsync(1)(cm => cm.ack().map(_ => cm))
        .take(input.size)
        .runWith(Sink.seq)

      result2.isReadyWithin(1.second) shouldEqual false
    }

    "publish via RPC and then consume through a simple queue again in the same JVM without autoAck" in {

      val queueName = "amqp-conn-it-spec-rpc-queue-" + System.currentTimeMillis()
      val queueDeclaration = QueueDeclaration(queueName)

      val input = Vector("one", "two", "three", "four", "five")

      val amqpRpcFlow = AmqpRpcFlow.committableFlow(
        AmqpSinkSettings(connectionProvider)
          .withRoutingKey(queueName)
          .withDeclaration(queueDeclaration),
        bufferSize = 10
      )
      val (rpcQueueF, probe) =
        Source(input)
          .map(s => ByteString(s))
          .map(bytes => OutgoingMessage(bytes, false, false))
          .viaMat(amqpRpcFlow)(Keep.right)
          .mapAsync(1)(cm => cm.ack().map(_ => cm.message))
          .toMat(TestSink.probe)(Keep.both)
          .run
      rpcQueueF.futureValue

      val amqpSink = AmqpSink.replyTo(
        AmqpReplyToSinkSettings(connectionProvider)
      )

      val amqpSource = AmqpSource.atMostOnceSource(
        NamedQueueSourceSettings(connectionProvider, queueName),
        bufferSize = 1
      )
      amqpSource
        .map(b => OutgoingMessage(b.bytes, false, false).withProperties(b.properties))
        .runWith(amqpSink)

      probe.toStrict(3.second).map(_.bytes.utf8String) shouldEqual input
    }

    "set routing key per message and consume them in the same JVM" in {
      def getRoutingKey(s: String) = s"key.${s}"

      val exchangeName = "amqp.topic." + System.currentTimeMillis()
      val queueName = "amqp-conn-it-spec-simple-queue-" + System.currentTimeMillis()
      val exchangeDeclaration = ExchangeDeclaration(exchangeName, "topic")
      val queueDeclaration = QueueDeclaration(queueName)
      val bindingDeclaration = BindingDeclaration(queueName, exchangeName).withRoutingKey(getRoutingKey("*"))

      val amqpSink = AmqpSink(
        AmqpSinkSettings(connectionProvider)
          .withExchange(exchangeName)
          .withDeclarations(immutable.Seq(exchangeDeclaration, queueDeclaration, bindingDeclaration))
      )

      val amqpSource = AmqpSource.atMostOnceSource(
        NamedQueueSourceSettings(connectionProvider, queueName)
          .withDeclarations(immutable.Seq(exchangeDeclaration, queueDeclaration, bindingDeclaration)),
        bufferSize = 10
      )

      val input = Vector("one", "two", "three", "four", "five")
      val routingKeys = input.map(s => getRoutingKey(s))
      Source(input)
        .map(s => OutgoingMessage(ByteString(s), false, false).withRoutingKey(getRoutingKey(s)))
        .runWith(amqpSink)
        .futureValue shouldEqual Done

      val result = amqpSource
        .take(input.size)
        .runWith(Sink.seq)
        .futureValue

      result.map(_.envelope.getRoutingKey) shouldEqual routingKeys
      result.map(_.bytes.utf8String) shouldEqual input
    }

    "declare connection that does not require server acks" in {
      val connectionProvider =
        AmqpDetailsConnectionProvider("localhost", 5672)

      val queueName = "amqp-conn-it-spec-fire-and-forget-" + System.currentTimeMillis()
      val queueDeclaration = QueueDeclaration(queueName)

      val amqpSink = AmqpSink.simple(
        AmqpSinkSettings(connectionProvider)
          .withRoutingKey(queueName)
          .withDeclaration(queueDeclaration)
      )

      val amqpSource = AmqpSource
        .committableSource(
          NamedQueueSourceSettings(connectionProvider, queueName)
            .withAckRequired(false)
            .withDeclaration(queueDeclaration),
          bufferSize = 10
        )
        .mapAsync(1)(cm => cm.ack().map(_ => cm))

      val input = Vector("one", "two", "three", "four", "five")
      Source(input).map(s => ByteString(s)).runWith(amqpSink).futureValue shouldEqual Done

      val result = amqpSource.take(input.size).runWith(Sink.seq)

      result.futureValue.map(_.message.bytes.utf8String) shouldEqual input
    }
  }
}
