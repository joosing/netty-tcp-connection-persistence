package netty.tcp.connection.persistence;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    final BlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();

    @BeforeEach
    void beforeEach() throws InterruptedException {
        client.init(new ChannelInitializer<>() {
            @Override
            protected void initChannel(NioSocketChannel ch) throws Exception {
                ch.pipeline()
                  .addLast(new StringDecoder())
                  .addLast(new ForwardHandler(responseQueue))
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
    void afterEach() throws InterruptedException {
        client.disconnect();
        server.shutdown();
    }

    @Test
    void tryPersistenceConnection_beforeServerStart() throws Exception {
        // Given : 서버 시작 전, 지속 연결을 시도하는 클라이언트
        final Future<Boolean> connectionFuture = client.connectUntilSuccess("127.0.0.1", 12345, Integer.MAX_VALUE, 100);

        // When : 2초 뒤 서버 시작
        Assertions.assertThatExceptionOfType(TimeoutException.class).isThrownBy(() -> connectionFuture.get(2000, TimeUnit.MILLISECONDS));
        server.start("0.0.0.0", 12345);

        // Then : 서버 시작 즉시 연결 수립
        Assertions.assertThat(connectionFuture.get()).isTrue();
        client.send("command");
        Assertions.assertThat(responseQueue.poll(1000, TimeUnit.MILLISECONDS)).isEqualTo("Hello");
    }

    @Test
    void serverRestart_inPersistenceConnection() throws Exception {
        // Given : 서버-클라이언트 연결 상태
        server.start("0.0.0.0", 12345);
        final Future<Boolean> connectionFuture = client.connectUntilSuccess("127.0.0.1", 12345, Integer.MAX_VALUE, 100);
        Assertions.assertThatNoException().isThrownBy(() -> {
            final Boolean result = connectionFuture.get(1000, TimeUnit.MILLISECONDS);
            Assertions.assertThat(result).isTrue();
        });

        // When : 서버 내린 후, 2초 후 재기동
        server.shutdown();
        client.send("command");
        Assertions.assertThat(responseQueue.poll(1000, TimeUnit.MILLISECONDS)).isNull();
        server.start("0.0.0.0", 12345);

        // Then : 서버 재기동 후 즉시 자동 재연결
        Thread.sleep(1000);
        client.send("command");
        Assertions.assertThat(responseQueue.poll(1000, TimeUnit.MILLISECONDS)).isEqualTo("Hello");
    }
}
