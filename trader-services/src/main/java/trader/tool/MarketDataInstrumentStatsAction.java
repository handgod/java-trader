package trader.tool;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import trader.common.beans.BeansContainer;
import trader.common.exchangeable.Exchange;
import trader.common.exchangeable.Exchangeable;
import trader.common.exchangeable.ExchangeableData;
import trader.common.exchangeable.Future;
import trader.common.exchangeable.MarketDayUtil;
import trader.common.tick.PriceLevel;
import trader.common.util.CSVDataSet;
import trader.common.util.CSVMarshallHelper;
import trader.common.util.CSVUtil;
import trader.common.util.CSVWriter;
import trader.common.util.DateUtil;
import trader.common.util.StringUtil;
import trader.common.util.StringUtil.KVPair;
import trader.common.util.TraderHomeUtil;
import trader.service.md.MarketData;
import trader.service.md.MarketDataProducer;
import trader.service.md.MarketDataProducerFactory;
import trader.service.ta.FutureBar;
import trader.service.ta.TimeSeriesLoader;
import trader.service.util.CmdAction;
import trader.simulator.SimMarketDataService;

/**
 * 对行情数据按日统计, 得到每天每合约的成交量,持仓等数据
 */
public class MarketDataInstrumentStatsAction implements CmdAction{

    private Map<String, MarketDataProducerFactory> producerFactories;
    private ExchangeableData exchangeableData;
    private LocalDate beginDate;
    private LocalDate endDate;
    private List<String> contracts;

    @Override
    public String getCommand() {
        return "marketData.instrumentStats";
    }

    @Override
    public void usage(PrintWriter writer) {
        writer.println("marketData instrumentStats  [--datadir=DATA_DIR] [--contracts=name1,name2] --beginDate=<BEGIN_DATE> --endDate=<END_DATE>");
        writer.println("\t更新合约每日统计数据");
    }

    @Override
    public int execute(BeansContainer beansContainer, PrintWriter writer, List<KVPair> options) throws Exception {
        producerFactories = SimMarketDataService.discoverProducerFactories();
        exchangeableData = new ExchangeableData(TraderHomeUtil.getDirectory(TraderHomeUtil.DIR_REPOSITORY), false);
        parseOptions(options);
        //如果没有指定合约,使用所有合约
        if ( contracts==null || contracts.size()==0 ) {
            contracts = new ArrayList<>();
            for(Exchange exchange:Exchange.getInstances()) {
                if ( exchange.isFuture()) {
                    contracts.addAll(exchange.getContractNames());
                }
            }
        }
        int totalCount=0;
        for(String c :contracts) {
            Exchange ex = Future.detectExchange(c);
            if ( ex==null ) {
                writer.println("未知合约: "+c);
                continue;
            }
            Future f = new Future(ex, c, c);
            int updated = updateInstrumentStatsData(writer, c);
            totalCount += updated;
        }
        writer.println("累计更新合约*交易日 "+totalCount);
        return 0;
    }

    protected void parseOptions(List<KVPair> options) {
        beginDate = null;
        endDate = null;
        for(KVPair kv:options) {
            if ( StringUtil.isEmpty(kv.v)) {
                continue;
            }
            switch(kv.k.toLowerCase()) {
            case "contracts":
                contracts = Arrays.asList(StringUtil.split(kv.v, ",|;"));
                break;
            case "begindate":
                beginDate = DateUtil.str2localdate(kv.v);
                break;
            case "enddate":
                endDate = DateUtil.str2localdate(kv.v);
                break;
            }
        }

        if (beginDate==null) {
            beginDate = MarketDayUtil.lastMarketDay(Exchange.SHFE, false);
        }
        if (endDate==null) {
            endDate = MarketDayUtil.lastMarketDay(Exchange.SHFE, false);
        }
    }

    /**
     * 更新品种的统计数据
     */
    protected int updateInstrumentStatsData(PrintWriter writer, String c) throws Exception
    {
        int totalDays = 0;
        writer.print("合约 "+c+" : ");writer.flush();
        Exchange ex = Future.detectExchange(c);
        Future contractFuture = new Future(ex, c, c);
        //加载已有统计数据
        TreeMap<String, String[]> rows = new TreeMap<>();
        if ( exchangeableData.exists(contractFuture, ExchangeableData.DAYSTATS, null)) {
            CSVDataSet csvDataSet = CSVUtil.parse(exchangeableData.load(contractFuture, ExchangeableData.DAYSTATS, null));
            while(csvDataSet.next()) {
                String key = csvDataSet.get(ExchangeableData.COLUMN_TRADINGDAY)+"-"+csvDataSet.get(ExchangeableData.COLUMN_INSTRUMENT_ID);
                rows.put(key, csvDataSet.getRow() );
            }
        }

        //统计指定日期的合约
        LocalDate currDate = beginDate;
        while(!currDate.isAfter(endDate)) {
            if ( MarketDayUtil.isMarketDay(ex, currDate)) {
                List<Future> instruments = Future.instrumentsFromMarketDay(currDate, c);
                for(Future f:instruments) {
                    FutureBar dayBar = loadDayBar(f, currDate);
                    if ( dayBar==null ) {
                        continue;
                    }
                    String[] row = new String[ExchangeableData.DAYSTATS.getColumns().length];
                    row[0] = f.id();
                    row[1] = DateUtil.date2str(currDate);
                    row[2] = dayBar.getVolume().toString();
                    row[3] = ""+dayBar.getOpenInterest();
                    row[4] = dayBar.getAmount().toString();

                    String key = row[1]+"-"+row[0];
                    rows.put(key, row);
                    writer.print("."); writer.flush();
                }
                totalDays++;
            }
            currDate = currDate.plusDays(1);
        }

        //保存统计数据
        CSVWriter csvWriter = new CSVWriter(ExchangeableData.DAYSTATS.getColumns());
        for(String[] row:rows.values()) {
            csvWriter.next();
            csvWriter.setRow(row);
        }
        exchangeableData.save(contractFuture, ExchangeableData.DAYSTATS, null, csvWriter.toString());
        writer.println(totalDays);
        return totalDays;
    }

    private FutureBar loadDayBar(Exchangeable e, LocalDate tradingDay) throws IOException
    {
        String producerType = MarketDataProducer.PROVIDER_CTP;
        CSVMarshallHelper csvMarshallHelper = createCSVMarshallHelper(producerType);
        MarketDataProducer mdProducer = createMarketDataProducer(producerType);

        FutureBar result = null;
        if ( exchangeableData.exists(e, ExchangeableData.DAY, tradingDay)) {
            CSVDataSet csvDataSet = CSVUtil.parse(exchangeableData.load(e, ExchangeableData.DAY, tradingDay));
            while(csvDataSet.next()) {
                FutureBar bar = FutureBar.fromCSV(csvDataSet, e);
                if ( bar.getEndTime().toLocalDate().equals(tradingDay)) {
                    result = bar;
                    break;
                }
            }
        }
        if ( result==null && exchangeableData.exists(e, ExchangeableData.TICK_CTP, tradingDay)) {
            String tickCsv = exchangeableData.load(e, ExchangeableData.TICK_CTP, tradingDay);
            List<MarketData> ticks = new ArrayList<>();
            CSVDataSet csvDataSet = CSVUtil.parse(tickCsv);
            while(csvDataSet.next()) {
                MarketData tick = mdProducer.createMarketData(csvMarshallHelper.unmarshall(csvDataSet.getRow()), tradingDay);
                ticks.add(tick);
            }
            List<FutureBar> bars2 = TimeSeriesLoader.marketDatas2bars(e, PriceLevel.DAY, ticks);
            result = bars2.get(0);
        }

        return result;
    }

    private CSVMarshallHelper createCSVMarshallHelper(String producerType) {
        MarketDataProducerFactory factory = producerFactories.get(producerType);
        if ( factory!=null ) {
            return factory.createCSVMarshallHelper();
        }
        return null;
    }

    private MarketDataProducer createMarketDataProducer(String producerType) {
        MarketDataProducerFactory factory = producerFactories.get(producerType);
        if ( factory!=null ) {
            return factory.create(null, Collections.emptyMap());
        }
        return null;
    }

}
