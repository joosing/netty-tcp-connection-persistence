package netty.tcp.connection.persistence;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

public class TcpConnectionPersistenceTest {
    final TcpClient client = new TcpClient();
    final TcpServer server = new TcpServer();
    final BlockingQueue<String> receiveQueue = new LinkedBlockingQueue<>();

    @BeforeEach
    void beforeEach() throws InterruptedException {
        client.init(new ChannelInitializer<>() {
            @Override
            protected void initChannel(NioSocketChannel ch) throws Exception {
                ch.pipeline()
                  .addLast(new StringDecoder())
                  .addLast(new ForwardHandler(receiveQueue))
                  .addLast(new StringEncoder());
            }
        });

        server.init(new ChannelInitializer<>() {
            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
                ch.pipeline()
                  .addLast(new StringDecoder())
                  .addLast(new EchoHandler())
                  .addLast(new StringEncoder());
            }
        });
    }

    @AfterEach
    void afterEach() {
        client.disconnect();
        server.shutdown();
    }

    @Test
    void tryPersistenceConnection_beforeServerStart() throws Exception {
        // Given : 서버 시작 전, 지속 연결을 시도하는 클라이언트
        final Future<Boolean> connectionFuture = client.connectUntilSuccess("127.0.0.1", 12345, Integer.MAX_VALUE, 100);

        // When : 2초 뒤 서버 시작
        Thread.sleep(2000);
        Assertions.assertThat(connectionFuture.isDone()).isFalse();
        server.start("0.0.0.0", 12345);

        // Then : 서버 시작 즉시 연결 수립
        Assertions.assertThat(connectionFuture.get()).isTrue();
        client.send("Hello");
        Assertions.assertThat(receiveQueue.poll(2000, TimeUnit.MILLISECONDS)).isEqualTo("Hello");
    }
}
