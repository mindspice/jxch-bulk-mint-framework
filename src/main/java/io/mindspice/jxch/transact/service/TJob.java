package io.mindspice.jxch.transact.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.mindspice.jxch.rpc.http.FullNodeAPI;
import io.mindspice.jxch.rpc.http.WalletAPI;
import io.mindspice.jxch.rpc.schemas.object.Coin;
import io.mindspice.jxch.rpc.schemas.object.MempoolItem;
import io.mindspice.jxch.rpc.schemas.object.SpendBundle;
import io.mindspice.jxch.rpc.util.ChiaUtils;
import io.mindspice.jxch.rpc.util.RPCException;
import io.mindspice.jxch.rpc.util.RequestUtils;
import io.mindspice.jxch.transact.logging.TLogLevel;
import io.mindspice.jxch.transact.logging.TLogger;
import io.mindspice.jxch.transact.settings.JobConfig;
import io.mindspice.jxch.transact.util.Pair;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;


public abstract class TJob  {

    public enum State{
        INIT,
        AWAITING_SYNC,
        AWAITING_CONFIRMATION,
        RETRYING,
        STARTED,
        EXCEPTION,
        SUCCESS,
        FAILED
    }
    protected final JobConfig config;
    protected final TLogger tLogger;
    protected final WalletAPI walletAPI;
    protected final FullNodeAPI nodeAPI;
    protected final String jobId = UUID.randomUUID().toString();
    protected final List<Coin> excludedCoins =  new CopyOnWriteArrayList<>();
    protected volatile int startHeight;
    protected volatile State state = State.INIT;

    public static Supplier<RPCException> dataExcept(String msg) {
        return () -> new RPCException("Required RPC call: " + msg + " returned Optional.empty");
    }



    public TJob(JobConfig config, TLogger tLogger, FullNodeAPI nodeAPI, WalletAPI walletAPI) {
        this.config = config;
        this.tLogger = tLogger;
        this.nodeAPI = nodeAPI;
        this.walletAPI = walletAPI;
    }

    public State getState() {
        return state;
    }

    public List<Coin> getExcludedCoins() {
        return excludedCoins;
    }

    public String getJobId() {
        return jobId;
    }

    protected Pair<Boolean, String> checkMempoolForTx(String sbHash) throws Exception {
        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Action: checkingMempoolForTransaction");
        Optional<String> txHash = nodeAPI.getAllMempoolItems().data()
                .orElseThrow(dataExcept("NodeAPI.getAllMempoolItems"))
                .entrySet().stream()
                .filter(e -> e.getValue().spendBundleName().equals(sbHash))
                .map(Map.Entry::getKey)
                .findFirst();
        return txHash.map(s -> new Pair<>(true, s)).orElseGet(() -> new Pair<>(false, ""));
    }

    protected SpendBundle getFeeBundle(Coin feeCoin, long feeAmount) throws RPCException {
        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Action: gettingFeeBundle");
        JsonNode feeBundleReq = new RequestUtils.SignedTransactionBuilder()
                .addAddition(config.changeTarget, feeCoin.amount() - feeAmount) // return amount not used for fee
                .addCoin(feeCoin)
                .addFee(feeAmount)
                .build();

        return walletAPI.createSignedTransaction(feeBundleReq).data().
                orElseThrow(dataExcept("WalletAPI.createSignedTransaction")).spendBundle();
    }

    protected long getSpendCost(SpendBundle spend) throws Exception {
        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Action: gettingSpendBundleCost");
        return nodeAPI.getSpendBundleInclusionCost(spend).data()
                .orElseThrow(dataExcept("NodeApi.getSpendBundleInclusionCost")).cost();
    }

    protected long getFeePerCostNeeded(long cost) throws RPCException {
        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Action: gettingFeePerCostNeeded");
        Map<String, MempoolItem> mempool =
                nodeAPI.getAllMempoolItems().data().orElseThrow(dataExcept("NodeAPI.getAllMempoolItems"));

        long totalMemCost = mempool.values().stream()
                .mapToLong(MempoolItem::cost)
                .sum();

        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | totalMemCost: " + totalMemCost);

        var sortedMempool = mempool.values().stream()
                .filter(i -> i.fee() > 0)
                .sorted(Comparator.comparing(MempoolItem::fee))
                .toList();

        var memSum = 0L;
        var feeNeeded = 0L;
        for (MempoolItem item : sortedMempool) {
            if (memSum < (cost * 1.05)) { // 5% extra  for a buffer
                memSum += item.cost();
                if (item.cost() > 0) {
                    feeNeeded = item.fee() / item.cost();
                }
            }
        }
        if (feeNeeded < 5) {
            return config.minFeePerCost;
        } else {
            return roundFpc(feeNeeded);
        }
    }

    protected static long roundFpc(long i) {
        var round = (i / 5) * 5;
        return (i - round > 2) ? round + 5 : round;
    }

    protected Coin getFeeCoin(long amount, List<Coin> excludedCoins) throws RPCException {
        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Action: gettingFeeCoin");

        var jsonNode = new RequestUtils.SpendableCoinBuilder()
                .setMinCoinAmount(amount)
                .setExcludedCoins(excludedCoins)
                .setWalletId(config.feeWalletId)
                .build();

        return walletAPI.getSpendableCoins(jsonNode)
                .data()
                .orElseThrow(dataExcept("WalletApi.getSpendableCoins"))
                .confirmedRecords()
                .stream().sorted(Comparator.comparing(c -> c.coin().amount()))
                .toList()
                .get(0).coin();
    }

    protected boolean waitForTxConfirmation(String txId, Coin txParentCoin) throws Exception {
        while (true) {
            tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                    " | Action: waitForConfirmation");
            Thread.sleep(10000);
            /* getTxStatus returns true if tx is no longer in the mempool
             Once we know it's not in the mempool, it needs to be confirmed
             the actual coin has been spent to confirm mint as successful */
            System.out.println("loop wait");
            if (!checkMempoolForTx(txId).first()) {
                Thread.sleep(10000);
                var mintCoinRecord = nodeAPI.getCoinRecordByName(ChiaUtils.getCoinId(txParentCoin));
                return mintCoinRecord.data().orElseThrow(dataExcept("NodeAPi.getCoinRecordsByName")).spent();
            }
        }
    }

    //todo test if there is an actual data object still returned when not found
    protected boolean txClearedMempool(String txId) throws RPCException {
        var resp = nodeAPI.getMempoolItemByTxId(txId, true);
        return resp.data().isEmpty();
    }



}