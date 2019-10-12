package ratelimit;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import static ratelimit.RateLimiterUtil.checkArgument;

/**
 * Guava有两种限流模式， 一种为稳定模式(SmoothBursty:令牌生成速度恒定)，
 * 一种为渐进模式(SmoothWarmingUp:令牌生成速度缓慢提升直到维持在一个稳定值) 主要区别在等待时间的计算上
 *
 * @author zhaoxuyang
 */
public abstract class RateLimiter {

    /**
     * 创建一个ReteLimiter实例
     *
     * @param qps 令牌数，理解为QPS
     * @return 一个"平滑突发"类型的SmoothBursty的实例
     */
    public static RateLimiter create(double qps) {
        return create(qps, SleepingStopwatch.createFromSystemTimer());
    }

    public static RateLimiter create(double qps, long warmupPeriod, TimeUnit unit) {
        checkArgument(warmupPeriod >= 0, String.format("预热指数不能为负: %s", warmupPeriod));
        double coldFactor = 3.0;
        return create(qps, warmupPeriod, unit, coldFactor,
                SleepingStopwatch.createFromSystemTimer());
    }

    /**
     * 令牌生成速度恒定SmoothBursty
     *
     * @param permitsPerSecond
     * @param stopwatch
     * @return
     */
    static RateLimiter create(double qps, SleepingStopwatch stopwatch) {
        double maxBurstSeconds = 1.0;
        RateLimiter rateLimiter = new SmoothRateLimiter.SmoothBursty(
                stopwatch, maxBurstSeconds);
        rateLimiter.setRate(qps);
        return rateLimiter;
    }

    /**
     * 渐进式增长SmoothWarmingUp，维持到一个稳定值
     *
     * @param permitsPerSecond
     * @param warmupPeriod
     * @param unit
     * @param coldFactor
     * @param stopwatch
     * @return
     */
    static RateLimiter create(double permitsPerSecond, long warmupPeriod,
            TimeUnit unit, double coldFactor, SleepingStopwatch stopwatch) {
        RateLimiter rateLimiter = new SmoothRateLimiter.SmoothWarmingUp(
                stopwatch, warmupPeriod, unit, coldFactor);
        rateLimiter.setRate(permitsPerSecond);
        return rateLimiter;
    }

    private static void checkPermits(int permits) {
        checkArgument(permits > 0, String.format("令牌数(%s)必须为正", permits));
    }

    private final SleepingStopwatch stopwatch;//秒表
    private volatile Object mutexDoNotUseDirectly;//互斥量不直接使用

    RateLimiter(SleepingStopwatch stopwatch) {
        if (stopwatch == null) {
            throw new NullPointerException();
        }
        this.stopwatch = stopwatch;
    }

    abstract void doSetRate(double qps, long nowMicros);

    abstract double doGetRate();

    abstract long queryEarliestAvailable(long nowMicros);

    abstract long reserveEarliestAvailable(int permits, long nowMicros);

    private Object mutex() {
        Object mutex = mutexDoNotUseDirectly;
        if (mutex == null) {
            synchronized (this) {
                mutex = mutexDoNotUseDirectly;
                if (mutex == null) {
                    mutex = new Object();
                    mutexDoNotUseDirectly = mutex;
                }
            }
        }
        return mutex;
    }

    /**
     * 通过这个接口设置令牌通每秒生成令牌的数量.
     * <ul>
     * <li> qps==qps 等价于 !Double.isNaN(qps) </li>
     * <li> 内部时间通过调用 {@link SmoothRateLimiter#doSetRate } 来实现</li>
     * </ul>
     * <pre>
     *
     *
     * </pre>
     *
     * @param permitsPerSecond
     */
    void setRate(double qps) {
        checkArgument(qps > 0.0 && (qps == qps), "QPS必须为正");
        synchronized (mutex()) {
            doSetRate(qps, stopwatch.readMicros());
        }
    }

    double getRate() {
        synchronized (mutex()) {
            return doGetRate();
        }
    }

    public double acquire() {
        return acquire(1);
    }

    /**
     * 主要用于获取permits个令牌，并计算需要等待多长时间，进而挂起等待，并将该值返回，
     * 主要通过reserve返回需要等待的时间，reserve中通过调用reserveAndGetWaitLength获取等待时间
     *
     * @param permits
     * @return
     */
    public double acquire(int permits) {
        //预定permits个令牌，并返回等待这些令牌所需的微妙数microsToWait
        long microsToWait = reserve(permits);
        //等待microsToWait微妙
        stopwatch.sleepMicrosUninterruptibly(microsToWait);
        //返回消耗的时间
        double result = 1.0 * microsToWait / TimeUnit.SECONDS.toMicros(1L);
        return result;
    }

    long reserve(int permits) {
        checkPermits(permits);
        synchronized (mutex()) {
            //通过对象锁进行同步，预定令牌，返回调用方需要等待的时间
            return reserveAndGetWaitLength(permits, stopwatch.readMicros());
        }
    }

    boolean tryAcquire() {
        return tryAcquire(1, 0, TimeUnit.MICROSECONDS);
    }

    boolean tryAcquire(int permits) {
        return tryAcquire(permits, 0, TimeUnit.MICROSECONDS);
    }

    boolean tryAcquire(long timeout, TimeUnit unit) {
        return tryAcquire(1, timeout, unit);
    }

    /**
     *
     * @param permits 令牌数（QPS）
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return
     */
    boolean tryAcquire(int permits, long timeout, TimeUnit unit) {
        long timeoutMicros = Math.max(unit.toMicros(timeout), 0);
        checkPermits(permits);
        long microsToWait;
        synchronized (mutex()) {
            long nowMicros = stopwatch.readMicros();
            if (!canAcquire(nowMicros, timeoutMicros)) {
                return false;
            } else {
                microsToWait = reserveAndGetWaitLength(permits, nowMicros);
            }
        }
        stopwatch.sleepMicrosUninterruptibly(microsToWait);
        return true;
    }

    private boolean canAcquire(long nowMicros, long timeoutMicros) {
        return queryEarliestAvailable(nowMicros) - timeoutMicros <= nowMicros;
    }

    long reserveAndGetWaitLength(int permits, long nowMicros) {
        long momentAvailable = reserveEarliestAvailable(permits, nowMicros);
        return Math.max(momentAvailable - nowMicros, 0);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "[qps = %.3f]", getRate());
    }

    abstract static class SleepingStopwatch {

        public static SleepingStopwatch createFromSystemTimer() {
            return new SleepingStopwatch() {
                final Stopwatch stopwatch = Stopwatch.createStarted();

                @Override
                protected long readMicros() {
                    return stopwatch.elapsed(TimeUnit.MICROSECONDS);
                }

                @Override
                protected void sleepMicrosUninterruptibly(long micros) {
                    if (micros > 0) {
                        RateLimiterUtil.sleepUninterruptibly(micros, TimeUnit.MICROSECONDS);
                    }
                }
            };
        }

        protected SleepingStopwatch() {
        }

        protected abstract long readMicros();

        protected abstract void sleepMicrosUninterruptibly(long micros);

    }

}
