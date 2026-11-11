package de.villagerplus.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

/** Persistent plaza and shop careers, separate from the full village codec. Stock lives in real owned chests. */
public record MarketData(Optional<BlockPos> plaza, List<Shop> shops, long nextPlan, long sales) {
 public record Shop(UUID owner, BlockPos origin, int facing, int kind, int progress, long opened, long lastSeen, List<String> palette, boolean deceased, long retryAt, boolean sitePrepared) {
  public static final Codec<Shop> CODEC = RecordCodecBuilder.create(i -> i.group(
   UUIDUtil.CODEC.fieldOf("owner").forGetter(Shop::owner), BlockPos.CODEC.fieldOf("origin").forGetter(Shop::origin),
   Codec.INT.fieldOf("facing").forGetter(Shop::facing), Codec.INT.fieldOf("kind").forGetter(Shop::kind),
   Codec.INT.optionalFieldOf("progress",0).forGetter(Shop::progress), Codec.LONG.optionalFieldOf("opened",0L).forGetter(Shop::opened),
   Codec.LONG.optionalFieldOf("last_seen",0L).forGetter(Shop::lastSeen), Codec.STRING.listOf().optionalFieldOf("palette",List.of()).forGetter(Shop::palette),
   Codec.BOOL.optionalFieldOf("deceased",false).forGetter(Shop::deceased), Codec.LONG.optionalFieldOf("retry_at",0L).forGetter(Shop::retryAt),
   Codec.BOOL.optionalFieldOf("site_prepared",false).forGetter(Shop::sitePrepared)).apply(i,Shop::new));
  public Shop(UUID owner, BlockPos origin, int facing, int kind, int progress, long opened, long lastSeen,
              List<String> palette, boolean deceased, long retryAt) {
   this(owner,origin,facing,kind,progress,opened,lastSeen,palette,deceased,retryAt,progress!=0||opened>0);
  }
  public Shop {
   // Older saves used progress=-1 as the prepared/first-step replay marker. Keep that
   // meaning when decoding them, and treat any paid/open shop as already prepared.
   if(progress!=0||opened>0) sitePrepared=true;
  }
  public Shop progress(int n, long now, boolean done) {
   return new Shop(owner,origin,facing,kind,n,done && opened==0 ? now : opened,now,palette,deceased,0,
    sitePrepared||n!=0||done);
  }
  public Shop retry(long until) { return new Shop(owner,origin,facing,kind,progress,opened,lastSeen,palette,deceased,until,sitePrepared); }
  public Shop died() { return new Shop(owner,origin,facing,kind,progress,opened,lastSeen,palette,true,retryAt,sitePrepared); }
  public Shop prepared(long now) { return new Shop(owner,origin,facing,kind,progress,opened,now,palette,deceased,0,true); }
 }
 public static final Codec<MarketData> CODEC = RecordCodecBuilder.create(i -> i.group(
  BlockPos.CODEC.optionalFieldOf("plaza").forGetter(MarketData::plaza), Shop.CODEC.listOf().optionalFieldOf("shops",List.of()).forGetter(MarketData::shops),
  Codec.LONG.optionalFieldOf("next_plan",0L).forGetter(MarketData::nextPlan), Codec.LONG.optionalFieldOf("sales",0L).forGetter(MarketData::sales)).apply(i,MarketData::new));
 public static MarketData empty() { return new MarketData(Optional.empty(),List.of(),0,0); }
}
