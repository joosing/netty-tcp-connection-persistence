package netty.tcp.connection.persistence.util;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ExecuteUntilSuccess {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final CountDownLatch cancel = new CountDownLatch(1);
    private Future<Boolean> future;
    private long intervalMillis;

    public Future<Boolean> begin(Supplier<Boolean> tryExecute, long timeoutMillis, long intervalMillis) {
        this.intervalMillis = intervalMillis;
        future = executor.submit(() -> {
            long runningTime = 0;

            // 타임아웃 시간 동안 반복
            while (runningTime < timeoutMillis) {

                // 성공 시도
                if (tryExecute.get()) {
                    return true;
                }

                // 취소 요청 대기 = 재시도 간격 대기
                if (cancel.await(intervalMillis, TimeUnit.MILLISECONDS)) {
                    return false;
                }

                // 시도 시간 누적
                runningTime += intervalMillis;
            }
            return false;
        });

        return future;
    }

    public void stop() {
        cancel.countDown();
        try {
            future.get(intervalMillis * 2, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("stop error", e);
        }
    }
}
