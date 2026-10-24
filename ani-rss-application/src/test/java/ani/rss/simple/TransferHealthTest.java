package ani.rss.simple;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransferHealthTest {
    private JsonObject task(long bytes,int seeds,double availability){JsonObject t=new JsonObject();t.addProperty("state","downloading");t.addProperty("progress",0.23);t.addProperty("completed",bytes);t.addProperty("num_seeds",seeds);t.addProperty("availability",availability);return t;}
    @Test void intermittentBytesWithoutCompleteSourceStillReleaseSlot(){
        TransferHealth health=new TransferHealth();JsonObject t=task(100_000_000,0,0.203);
        assertNull(health.assess("a",t,1_000,true));t.addProperty("completed",100_300_000);
        assertTrue(health.assess("a",t,1_000+TransferHealth.WINDOW,true).contains("缺少完整下载源"));
    }
    @Test void sustainedFastTransferRetainsSlot(){TransferHealth h=new TransferHealth();JsonObject t=task(0,5,2);h.assess("a",t,1_000,false);t.addProperty("completed",300_000_000);assertNull(h.assess("a",t,1_000+TransferHealth.WINDOW,false));}
    @Test void upgradesYieldBeforeMissingEpisodesAtTheSameRate(){
        JsonObject t=task(0,5,2);TransferHealth missing=new TransferHealth(),upgrade=new TransferHealth();missing.assess("a",t,1_000,false);upgrade.assess("a",t,1_000,true);
        t.addProperty("completed",6_000_000);assertNull(missing.assess("a",t,1_000+TransferHealth.WINDOW,false));assertNotNull(upgrade.assess("a",t,1_000+TransferHealth.WINDOW,true));
    }
    @Test void checkingAndQueuedTimeDoNotCountAsSlowTransfer(){
        TransferHealth h=new TransferHealth();JsonObject t=task(0,0,0);h.assess("a",t,1_000,false);t.addProperty("state","checkingDL");assertNull(h.assess("a",t,1_000+TransferHealth.WINDOW,false));
        t.addProperty("state","queuedDL");assertNull(h.assess("a",t,1_000+2*TransferHealth.WINDOW,false));t.addProperty("state","downloading");assertNull(h.assess("a",t,1_000+3*TransferHealth.WINDOW,false));
    }
    @Test void restartedOrDecreasedCountersReceiveANewWindow(){TransferHealth h=new TransferHealth();h.assess("a",task(1000,0,0),1_000,false);assertNull(h.assess("a",task(0,0,0),1_000+TransferHealth.WINDOW,false));}
    @Test void nearCompleteUsefulTransferIsNotInterrupted(){TransferHealth h=new TransferHealth();JsonObject t=task(9800,0,0.5);t.addProperty("progress",0.99);t.addProperty("total_size",10000);h.assess("a",t,1_000,true);t.addProperty("completed",9900);assertNull(h.assess("a",t,1_000+TransferHealth.WINDOW,true));}
    @Test void nearCompleteLargeFileStillYieldsIfItsRemainderWouldTakeHours(){TransferHealth h=new TransferHealth();JsonObject t=task(990_000_000,0,0.5);t.addProperty("progress",0.99);t.addProperty("total_size",1_000_000_000);h.assess("a",t,1_000,true);t.addProperty("completed",990_000_010);assertNotNull(h.assess("a",t,1_000+TransferHealth.WINDOW,true));}
}
