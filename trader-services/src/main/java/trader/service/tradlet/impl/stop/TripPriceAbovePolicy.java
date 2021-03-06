package trader.service.tradlet.impl.stop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import trader.common.beans.BeansContainer;
import trader.common.util.ConversionUtil;
import trader.common.util.PriceUtil;
import trader.common.util.StringUtil;
import trader.service.md.MarketData;
import trader.service.ta.TripBarrierDef;
import trader.service.ta.TripTickBarrier;
import trader.service.ta.TripTickBarrier.Barrier;
import trader.service.tradlet.Playbook;

/**
 * 三重界限超过价格停止
 */
public class TripPriceAbovePolicy extends AbsStopPolicy {

    private String config;
    private TripBarrierDef tripBarrierDef;
    private TripTickBarrier tripBarrier;

    TripPriceAbovePolicy(BeansContainer beansContainer, Playbook playbook){
        super(beansContainer);
        config = PBATTR_TRIP_PRICE_ABOVE.getString(playbook);
        if ( !StringUtil.isEmpty(config)) {
            long top=0, bottom=0, maxTime= DEFAULT_PRICE_STEP_TIME;
            for(String[] kv:StringUtil.splitKVs(config)) {
                if ( kv.length<2 ){
                    continue;
                }
                String k = kv[0], v=kv[1];
                if ( StringUtil.equalsIgnoreCase("top", k)) {
                    top = PriceUtil.str2long(v);
                }else if ( StringUtil.equalsIgnoreCase("bottom", k)) {
                    bottom = PriceUtil.str2long(v);
                }else if ( StringUtil.equalsIgnoreCase("maxTime", v)) {
                    maxTime = ConversionUtil.str2seconds(v);
                }
            }
            if ( top>0 && bottom>0 && maxTime>0 ) {
                tripBarrierDef = new TripBarrierDef(top, bottom, (int)maxTime);
            }
        }
    }

    public static boolean needPolicy(Playbook playbook) {
        String config = PBATTR_TRIP_PRICE_ABOVE.getString(playbook);
        return !StringUtil.isEmpty(config);
    }

    @Override
    public JsonElement toJson() {
        JsonObject json = new JsonObject();
        if ( tripBarrierDef!=null ) {
            json.addProperty("top", PriceUtil.long2str(tripBarrierDef.maxPrice));
            json.addProperty("bottom", PriceUtil.long2str(tripBarrierDef.minPrice));
            json.addProperty("maxTime", ""+(tripBarrierDef.maxTime/1000));
            json.addProperty("inBarrier", tripBarrier!=null);
        }
        return json;
    }

    public boolean needRebuild(Playbook playbook) {
        String config2 = PBATTR_TRIP_PRICE_ABOVE.getString(playbook);

        return !StringUtil.equals(config, config2);
    }

    @Override
    public String needStop(Playbook playbook, MarketData tick) {
        String result = null;
        if ( tripBarrier!=null) {
            Barrier barrier = null;
            if (tick!=null ) {
                barrier = tripBarrier.update(tick);
            }else {
                barrier = tripBarrier.update(mtService.currentTimeMillis());
            }
            switch(barrier) {
            case Top: //时间或最高价, 触发信号
                result = PBACTION_TRIP_PRICE_ABOVE+" "+PriceUtil.long2str(tripBarrierDef.maxPrice);
                break;
            case End:
                result = PBACTION_TRIP_PRICE_ABOVE+" "+(tripBarrierDef.maxTime/1000);
                break;
            case Bottom: //最低价之下, 信号消失
                break;
            }
            if ( barrier!=null ) {
                tripBarrier = null;
            }
        } else if ( tripBarrierDef!=null&&tick!=null ){
            long price = tick.lastPrice;
            //高于最高价, 直接触发信号
            if ( price>=tripBarrierDef.maxPrice ) {
                result = PBACTION_TRIP_PRICE_ABOVE+" "+PriceUtil.long2str(tripBarrierDef.maxPrice);
            }else if (price>=tripBarrierDef.minPrice) {
                //最高价~~最低价之间, 进入三重界限
                tripBarrier = new TripTickBarrier(tripBarrierDef, tick);
            }
        }
        return result;
    }

}
