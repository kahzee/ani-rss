package ani.rss.simple;

import com.google.gson.JsonObject;
import java.util.*;

/** A little intermittent traffic must not keep a slot forever. Uses verified byte growth, not rounded percent. */
final class TransferHealth {
    static final long WINDOW = 5 * 60_000L;
    private record Sample(long at,long completed,int slowWindows) {}
    private final Map<String,Sample> samples = new HashMap<>();

    String assess(String hash,JsonObject task,long now,boolean upgrade) {
        String state=task.has("state")?task.get("state").getAsString().toLowerCase(Locale.ROOT):"";
        double progress=task.has("progress")?task.get("progress").getAsDouble():0;
        long bytes=number(task,"completed",number(task,"downloaded",0));
        if(progress>=1 || !Set.of("downloading","stalleddl","metadl").contains(state)
                || task.has("force_start") && task.get("force_start").getAsBoolean()) {samples.remove(hash);return null;}
        Sample first=samples.get(hash);
        if(first==null || now<first.at() || bytes<first.completed() || now-first.at()>2*WINDOW) {samples.put(hash,new Sample(now,bytes,0));return null;}
        long elapsed=now-first.at();if(elapsed<WINDOW)return null;
        long growth=bytes-first.completed();double rate=growth*1000d/Math.max(1,elapsed);
        boolean incompleteSource=number(task,"num_seeds",-1)==0 && task.has("availability")
                && task.get("availability").getAsDouble()>=0 && task.get("availability").getAsDouble()<1;
        // Let small/near-complete transfers finish if they are still making useful progress.
        long size=number(task,"total_size",number(task,"size",0));
        boolean nearlyDone=progress>=0.98 && growth>0 && size>0 && Math.max(0,size-bytes)/Math.max(rate,0.001)<=600;
        int threshold=upgrade?8:2;
        int slow=rate<threshold*1024L&&!nearlyDone?first.slowWindows()+1:0;
        samples.put(hash,new Sample(now,bytes,slow));
        if(slow<2)return null;
        return (incompleteSource?"当前下载源供给不足；":"")+"连续两个 5 分钟窗口平均不足 "+threshold+" KB/s，已让出名额";
    }
    void forget(String hash){samples.remove(hash);}
    private static long number(JsonObject task,String key,long fallback){return task.has(key)&&!task.get(key).isJsonNull()?task.get(key).getAsLong():fallback;}
}
