package io.mindspice.jxch.transact.jobs.mint;

import io.mindspice.jxch.rpc.http.FullNodeAPI;
import io.mindspice.jxch.rpc.http.WalletAPI;
import io.mindspice.jxch.transact.logging.TLogLevel;
import io.mindspice.jxch.transact.logging.TLogger;
import io.mindspice.jxch.transact.settings.JobConfig;
import io.mindspice.mindlib.data.Pair;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.stream.IntStream;


public abstract class MintService implements Runnable {
    protected final ScheduledExecutorService executor;
    protected final JobConfig config;
    protected final TLogger tLogger;
    protected final FullNodeAPI nodeAPI;
    protected final WalletAPI walletAPI;

    protected final ConcurrentLinkedQueue<MintItem> mintQueue = new ConcurrentLinkedQueue<>();

    protected boolean stopped = true;
    protected ScheduledFuture<?> taskRef;
    protected long lastTime;

    public MintService(ScheduledExecutorService scheduledExecutor, JobConfig config, TLogger tLogger,
            FullNodeAPI nodeAPI, WalletAPI walletAPI) {
        this.executor = scheduledExecutor;
        this.config = config;
        this.tLogger = tLogger;
        this.nodeAPI = nodeAPI;
        this.walletAPI = walletAPI;
    }

    public int stop() {
        stopped = true;
        return mintQueue.size();
    }

    public void start() {
        taskRef = executor.scheduleAtFixedRate(
                this,
                0,
                config.queueCheckInterval,
                TimeUnit.SECONDS
        );
        stopped = false;
        lastTime = Instant.now().getEpochSecond();
    }

    private void terminate() {
        taskRef.cancel(true);
    }

    public int size() {
        return mintQueue.size();
    }

    public boolean submit(MintItem item) {
        if (stopped) { return false; }
        mintQueue.add(item);
        return true;
    }

    public boolean submit(List<MintItem> items) {
        if (stopped) { return false; }
        mintQueue.addAll(items);
        return true;
    }

    // Override to handle what to do with failed mints
    protected abstract void onFail(List<MintItem> mintItems);

    // Override if you have actions that need performed on finished mints
    // returns the original items, as well as their on chain NFT Ids
    protected abstract void onFinish(Pair<List<MintItem>, List<String>> itemsAndNftIds);

    public void run() {

        if (mintQueue.isEmpty()) {
            if (stopped) { terminate(); } else { return; }
        }

        long nowTime = Instant.now().getEpochSecond();
        if (mintQueue.size() >= config.jobSize || nowTime - lastTime >= config.queueMaxWaitSec) {
            lastTime = nowTime;

            MintJob mintJob = new MintJob(config, tLogger, nodeAPI, walletAPI);
            List<MintItem> mintItems = IntStream.range(0, Math.min(config.jobSize, mintQueue.size()))
                    .mapToObj(i -> mintQueue.poll())
                    .filter(Objects::nonNull).toList();
            mintJob.addMintItem(mintItems);
            try {
                Pair<Boolean, List<String>> mintResult = executor.submit(mintJob).get();
                if (mintResult.first()) {
                    onFinish(new Pair<>(mintItems, mintResult.second()));
                } else {
                    onFail(mintItems);
                }
            } catch (Exception ex) {
                tLogger.log(this.getClass(), TLogLevel.ERROR,
                            "MintJob: " + mintJob.getJobId() + " Failed" +
                                   " | Exception: " + ex.getMessage(), ex);
                onFail(mintItems);
            }
        }
    }
}


