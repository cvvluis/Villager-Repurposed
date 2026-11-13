package de.villagerplus.dev;

import de.villagerplus.*;
import de.villagerplus.village.*;
import de.villagerplus.job.*;
import de.villagerplus.build.*;
import java.util.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.phys.AABB;

/** Explicit development-only integration checks. Fixtures are confined to the named CTOV test world's remote test area. */
public final class Checks090 {
 private static int passed;
 private static final BlockPos A=new BlockPos(12000,201,12000), B=new BlockPos(12200,201,12000);
 static void check(boolean ok,String label) {if(!ok)throw new IllegalStateException("CHECK FAILED: "+label);passed++;System.out.println("VP090 PASS "+label);}
 private static VillageRef fixture(ServerLevel l,BlockPos at) {
  l.getChunk(at);l.setBlock(at.below(),Blocks.STONE.defaultBlockState(),3);l.setBlock(at,Blocks.BELL.defaultBlockState(),3);
  var be=l.getBlockEntity(at);be.setAttached(VillagerPlus.NETWORK,TradeNetwork.create(at));be.setAttached(VillagerPlus.MARKET,MarketData.empty()); var data=new VillageData();be.setAttached(VillagerPlus.VILLAGE,data);
  BlockPos chest=at.east(3);l.setBlock(chest.below(),Blocks.STONE.defaultBlockState(),3);l.setBlock(chest,Blocks.CHEST.defaultBlockState(),3);
  data.chest=Optional.of(chest);Villages.noteChunk(l.getChunkAt(at));return new VillageRef(at,data,be);
 }
 public static VillageRef fixtureForEquipment(ServerLevel level, BlockPos at) { return fixture(level, at); }
 public static void assertEquipment(boolean ok, String label) { check(ok, label); }
 private static Object call(Class<?> cls,String name,Class<?>[] signature,Object... args)throws Exception {var m=cls.getDeclaredMethod(name,signature);m.setAccessible(true);return m.invoke(null,args);}
 private static ItemStack card(ServerLevel l,ServerPlayer p,VillageRef ref){
  Network.giveCard(l,p,ref);
  for(int i=p.getInventory().getContainerSize()-1;i>=0;i--){
   var s=p.getInventory().getItem(i);var data=s.get(DataComponents.CUSTOM_DATA);
   if(!s.is(de.villagerplus.block.ModItems.DORFKARTE)||data==null)continue;
   var tag=data.copyTag();
   if(tag.getStringOr("dim","").equals(l.dimension().identifier().toString())&&tag.getIntOr("x",0)==ref.bell().getX()
    &&tag.getIntOr("y",0)==ref.bell().getY()&&tag.getIntOr("z",0)==ref.bell().getZ())return s;
  }
  throw new IllegalStateException("No card for requested test village");
 }
 private static void redeem(ServerLevel l,ServerPlayer p,BlockPos board,ItemStack card)throws Exception{call(Network.class,"redeem",new Class[]{ServerLevel.class,ServerPlayer.class,BlockPos.class,ItemStack.class},l,p,board,card);}
 private static int total(Container c,Item item){return JobUtil.count(c,s->s.is(item));}

 private static void villagerInfoChecks(ServerLevel level,ServerPlayer player,VillageRef ref) {
  BlockPos chestPos=ref.bell().east(9);level.setBlock(chestPos.below(),Blocks.STONE.defaultBlockState(),3);level.setBlock(chestPos,Blocks.CHEST.defaultBlockState(),3);
  Container chest=(Container)level.getBlockEntity(chestPos);chest.setItem(0,new ItemStack(Items.BREAD,2));
  Villager villager=EntityTypes.VILLAGER.spawn(level,ref.bell().east(6),EntitySpawnReason.COMMAND);villager.addTag("vp090_fixture");villager.setNoAi(true);
  VillagerMemory memory=villager.getAttachedOrCreate(VillagerPlus.MEMORY);memory.name="InfoTester";memory.homeChest=Optional.of(chestPos);
  villager.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET,
   new net.minecraft.world.entity.ai.memory.WalkTarget(ref.bell().east(20),0.5F,1));
  ref.data().wanted.clear();ref.data().wanted.add("minecraft:oak_door#1");ref.dirty();
  List<String> lines=VillagerInfo.lines(level,villager,player);
  check(lines.stream().anyMatch(x->x.startsWith("Ziel|")&&x.contains(ref.bell().east(20).toShortString())),"villager info shows current walk target coordinates separately");
  check(lines.stream().anyMatch(x->x.startsWith("Tätigkeit|")),"villager info keeps the current activity in its own row");
  check(lines.stream().anyMatch(x->x.startsWith("Privattruhe|")&&x.contains("2×")),"villager info lists private chest contents");
  check(lines.stream().anyMatch(x->x.startsWith("Fehlendes Baumaterial|")&&x.contains("minecraft:oak_door#1")),"villager info lists the village construction shortage");
  villager.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
  lines=VillagerInfo.lines(level,villager,player);
  check(lines.stream().anyMatch(x->x.equals("Ziel|kein aktives Wegziel|-1")),"villager info labels a resident without an active walk target");
  ref.data().wanted.clear();ref.dirty();villager.discard();level.setBlock(chestPos,Blocks.AIR.defaultBlockState(),3);level.setBlock(chestPos.below(),Blocks.GRASS_BLOCK.defaultBlockState(),3);
 }

 private static void farmingLivestockForestryChecks(ServerLevel level) {
  check(FarmerJob.foodTarget(0)==0&&FarmerJob.foodTarget(3)==15,
   "farm reserve target scales at five food units per resident");
  check(Livestock.canBreed(2,7,2)&&!Livestock.canBreed(1,2,8)&&!Livestock.canBreed(2,8,8)
   &&!Livestock.canBreed(2,7,1),"livestock breeding requires two adults, two feed, and stays within eight per species");
  BlockPos tree=A.offset(80,0,80);
  level.setBlock(tree.below(),Blocks.DIRT.defaultBlockState(),3);
  level.setBlock(tree,Blocks.OAK_LOG.defaultBlockState(),3);
  level.setBlock(tree.above(4),Blocks.OAK_LEAVES.defaultBlockState(),3);
  check(Woodcutting.isHarvestableTree(level,tree),"rooted natural log with a canopy is harvestable");
  level.setBlock(tree.above(4),Blocks.AIR.defaultBlockState(),3);
  check(!Woodcutting.isHarvestableTree(level,tree),"built or canopy-free wood is protected from tree harvesting");
  level.setBlock(tree,Blocks.AIR.defaultBlockState(),3);
  level.setBlock(tree.below(),Blocks.GRASS_BLOCK.defaultBlockState(),3);
 }
 private static void lot(Container c,Item item,int total){c.clearContent();int i=0;while(total>0){int n=Math.min(64,total);c.setItem(i++,new ItemStack(item,n));total-=n;}}
 private static void constructionChecks(ServerLevel level,VillageRef ref,Container box) {
  BlockPos origin=A.offset(40,0,40);Architect.Design design=Architect.design(Architect.Kind.HOUSE,0x12345L);
  Palette palette=new Palette(Blocks.OAK_PLANKS,Blocks.BIRCH_PLANKS,Blocks.OAK_LOG,Blocks.COBBLESTONE,Blocks.OAK_PLANKS,Blocks.OAK_STAIRS,Blocks.OAK_SLAB,Blocks.GLASS_PANE,Blocks.OAK_PLANKS,Blocks.OAK_DOOR,Blocks.OAK_FENCE);
  List<Step> legacy=Architect.build(design,palette,origin,Direction.SOUTH);
  List<Step> ordered=ConstructionSite.order(legacy,origin,design,palette);
  java.util.Map<BlockPos,Step> stepsByPosition=new java.util.LinkedHashMap<>();for(Step step:ordered)stepsByPosition.put(step.pos(),step);
  int last=-1;boolean monotonic=true;for(Step step:ordered){int phase=ConstructionSite.phaseOf(step,origin,design,palette,stepsByPosition);if(phase<last)monotonic=false;last=phase;}
  check(monotonic,"construction phase ordering monotonic");
  check(!legacy.equals(ordered),"new construction order differs from legacy cursor order");
  check(ref.bellEntity().getAttachedOrElse(VillagerPlus.CONSTRUCTION,null)==null,"legacy plan has no phase metadata");
  List<Integer> ends=ConstructionSite.phaseEnds(ordered,origin,design,palette);
  ConstructionSite planned=new ConstructionSite(origin,List.of(),ends,false,palette.ids());var encoded=ConstructionSite.CODEC.encodeStart(JsonOps.INSTANCE,planned).getOrThrow();
  ConstructionSite decoded=ConstructionSite.CODEC.parse(JsonOps.INSTANCE,encoded).getOrThrow();
  check(ConstructionSite.forPlan(legacy,origin,design,palette,null).equals(legacy),"legacy cursor order remains unchanged");
  check(ConstructionSite.forPlan(legacy,origin,design,palette,planned).equals(ordered),"opted-in plan uses semantic order");
  check(decoded.equals(planned)&&decoded.phaseEnds().get(4)==ordered.size()&&decoded.paletteIds().equals(palette.ids()),"phase order and exact material palette persist by codec");
  ref.bellEntity().setAttached(VillagerPlus.CONSTRUCTION,planned);
  box.clearContent();box.setItem(0,new ItemStack(Items.SCAFFOLDING,32));box.setItem(1,new ItemStack(Items.CRAFTING_TABLE,2));
  int scaffoldStart=total(box,Items.SCAFFOLDING),tableStart=total(box,Items.CRAFTING_TABLE);
  ConstructionSite prepared=ConstructionSite.prepare(level,planned,ordered,origin,Direction.SOUTH,box);
  check(prepared.prepared()&&!prepared.temporary().isEmpty(),"worksite setup places only stocked supported props");
  int scaffoldPlaced=(int)prepared.temporary().stream().filter(t->!t.workbench()).count(),tablesPlaced=(int)prepared.temporary().stream().filter(ConstructionSite.TemporaryBlock::workbench).count();
  check(total(box,Items.SCAFFOLDING)==scaffoldStart-scaffoldPlaced&&total(box,Items.CRAFTING_TABLE)==tableStart-tablesPlaced,"worksite props consume exact stock");
  check(ConstructionSite.prepare(level,prepared,ordered,origin,Direction.SOUTH,box).equals(prepared),"prepared worksite cannot duplicate items on retry");
  ConstructionSite codecPrepared=ConstructionSite.CODEC.parse(JsonOps.INSTANCE,ConstructionSite.CODEC.encodeStart(JsonOps.INSTANCE,prepared).getOrThrow()).getOrThrow();
  check(codecPrepared.equals(prepared),"temporary positions survive persistence round trip");
  ConstructionSite.TemporaryBlock mined=prepared.temporary().stream().filter(t->!t.workbench()).findFirst().orElse(null);
  ConstructionSite.TemporaryBlock replaced=prepared.temporary().stream().filter(ConstructionSite.TemporaryBlock::workbench).findFirst().orElse(null);
  if(mined!=null)level.setBlock(mined.pos(),Blocks.AIR.defaultBlockState(),3);
  if(replaced!=null)level.setBlock(replaced.pos(),Blocks.GOLD_BLOCK.defaultBlockState(),3);
  int refundsScaffold=0,refundsTable=0;
  for(var temp:prepared.temporary())if(!temp.equals(mined)&&!temp.equals(replaced)){
   if(level.getBlockState(temp.pos()).is(temp.workbench()?Blocks.CRAFTING_TABLE:Blocks.SCAFFOLDING)){if(temp.workbench())refundsTable++;else refundsScaffold++;}
  }
  ConstructionSite cleaned=ConstructionSite.cleanup(level,prepared,box);
  check(cleaned.temporary().isEmpty(),"loaded worksite cleanup clears persistent records");
  check(total(box,Items.SCAFFOLDING)==scaffoldStart-scaffoldPlaced+refundsScaffold&&total(box,Items.CRAFTING_TABLE)==tableStart-tablesPlaced+refundsTable,
   "cleanup refunds only still-matching tracked blocks");
  if(replaced!=null)check(level.getBlockState(replaced.pos()).is(Blocks.GOLD_BLOCK),"cleanup preserves player replacement block");
  if(replaced!=null)level.setBlock(replaced.pos(),Blocks.AIR.defaultBlockState(),3);
  box.clearContent();ConstructionSite noStock=ConstructionSite.prepare(level,planned,ordered,origin,Direction.SOUTH,box);
  check(noStock.prepared()&&noStock.temporary().isEmpty()&&total(box,Items.SCAFFOLDING)==0&&total(box,Items.CRAFTING_TABLE)==0,"missing optional props are skipped without fabrication");
  ref.bellEntity().removeAttached(VillagerPlus.CONSTRUCTION);box.clearContent();
 }
 private static boolean craftRegistered(Ctx ctx,Container box,BlockPos workPos,java.util.function.Predicate<ItemStack> output,int cap)throws Exception {
  Class<?> helper=Class.forName("de.villagerplus.job.RecipeCrafting");
  var method=helper.getDeclaredMethod("craft",Ctx.class,Container.class,BlockPos.class,java.util.function.Predicate.class,int.class);method.setAccessible(true);
  return (Boolean)method.invoke(null,ctx,box,workPos,output,cap);
 }
 private static boolean sameContents(Container box,List<ItemStack> before) {
  if(box.getContainerSize()!=before.size())return false;
  for(int i=0;i<before.size();i++) {
   ItemStack old=before.get(i),now=box.getItem(i);
   if(old.isEmpty()!=now.isEmpty())return false;
   if(!old.isEmpty()&&(!ItemStack.isSameItemSameComponents(old,now)||old.getCount()!=now.getCount()))return false;
  }
  return true;
 }
 private static List<ItemStack> snapshot(Container box) {
  List<ItemStack> copy=new ArrayList<>();for(int i=0;i<box.getContainerSize();i++)copy.add(box.getItem(i).copy());return copy;
 }
 private static void recipeCraftingChecks(ServerLevel level,VillageRef ref,Container box)throws Exception {
  Villager worker=EntityTypes.VILLAGER.spawn(level,ref.bell().east(4),EntitySpawnReason.COMMAND);worker.addTag("vp090_fixture");worker.setInvulnerable(true);worker.setNoAi(true);
  Ctx ctx=new Ctx(level,worker,JobManager.state(worker),worker.getAttachedOrCreate(VillagerPlus.MEMORY),ref,level.getGameTime());
  BlockPos chest=ref.data().chest.orElseThrow();
  boolean hasRails=level.recipeAccess().getRecipes().stream().anyMatch(h->h.value() instanceof net.minecraft.world.item.crafting.ShapedRecipe r
   &&r.assemble(net.minecraft.world.item.crafting.CraftingInput.EMPTY).is(Items.RAIL));
  check(hasRails,"server recipe manager contains rail crafting recipe");
  box.clearContent();for(int i=0;i<6;i++)box.setItem(i,new ItemStack(Items.IRON_INGOT,1));box.setItem(6,new ItemStack(Items.STICK,1));
  check(craftRegistered(ctx,box,chest,s->s.is(Items.RAIL),32),"rail recipe accepts ingredients split into single-item stacks");
  check(total(box,Items.IRON_INGOT)==0&&total(box,Items.STICK)==0&&total(box,Items.RAIL)==16,"rail recipe consumes exact ingredients and yields registered quantity");
  box.clearContent();for(int i=0;i<5;i++)box.setItem(i,new ItemStack(Items.IRON_INGOT,1));box.setItem(5,new ItemStack(Items.STICK,1));
  List<ItemStack> before=snapshot(box);
  check(!craftRegistered(ctx,box,chest,s->s.is(Items.RAIL),32)&&sameContents(box,before),"insufficient rail ingredients fail without mutation");
  box.clearContent();box.setItem(0,new ItemStack(Items.IRON_INGOT,1));box.setItem(1,new ItemStack(Items.STICK,1));
  check(!Craft.run(ctx,List.of(Craft.recipe("Paper",()->new ItemStack(Items.PAPER,3),Craft.of(Items.SUGAR_CANE,3))))
   &&total(box,Items.IRON_INGOT)==1&&total(box,Items.STICK)==1&&total(box,Items.RAIL)==0,"profession craft cannot select unrelated rail output");
  box.clearContent();for(int i=0;i<6;i++)box.setItem(i,new ItemStack(Items.IRON_INGOT,1));box.setItem(6,new ItemStack(Items.STICK,1));
  check(craftRegistered(ctx,box,chest,s->s.is(Items.RAIL),16),"rail output below stock cap crafts");
  for(int i=0;i<6;i++)box.setItem(i+1,new ItemStack(Items.IRON_INGOT,1));box.setItem(7,new ItemStack(Items.STICK,1));before=snapshot(box);
  check(!craftRegistered(ctx,box,chest,s->s.is(Items.RAIL),16)&&sameContents(box,before),"recipe output cap blocks additional production");
  boolean hasCake=level.recipeAccess().getRecipes().stream().anyMatch(h->h.value() instanceof net.minecraft.world.item.crafting.ShapedRecipe r
   &&r.assemble(net.minecraft.world.item.crafting.CraftingInput.EMPTY).is(Items.CAKE));
  check(hasCake,"server recipe manager contains cake recipe with container remainders");
  box.clearContent();
  // A mod can make ingredient containers stackable; keep every ingredient slot occupied after crafting.
  for(int i=0;i<3;i++){ItemStack milk=new ItemStack(Items.MILK_BUCKET,2);milk.set(DataComponents.MAX_STACK_SIZE,16);box.setItem(i,milk);}
  box.setItem(3,new ItemStack(Items.SUGAR,4));box.setItem(4,new ItemStack(Items.EGG,2));box.setItem(5,new ItemStack(Items.SUGAR,4));
  box.setItem(6,new ItemStack(Items.WHEAT,4));box.setItem(7,new ItemStack(Items.WHEAT,4));box.setItem(8,new ItemStack(Items.WHEAT,4));
  for(int i=9;i<box.getContainerSize();i++)box.setItem(i,new ItemStack(Items.COBBLESTONE,64));
  before=snapshot(box);
  check(!craftRegistered(ctx,box,chest,s->s.is(Items.CAKE),64)&&sameContents(box,before),"full output and remainder inventory rejects cake craft atomically");
  check(total(box,Items.MILK_BUCKET)==6&&total(box,Items.BUCKET)==0&&total(box,Items.CAKE)==0,"failed remainder transaction preserves milk buckets and creates no cake");
  box.clearContent();
 }
 private static String professionPath(Villager villager) {
  return villager.getVillagerData().profession().unwrapKey().map(k->k.identifier().getPath()).orElse("");
 }
 private static void apprenticeChecks(ServerLevel level,VillageRef ref) {
  Villager qualified=EntityTypes.VILLAGER.spawn(level,ref.bell().east(8),EntitySpawnReason.COMMAND);
  Villager novice=EntityTypes.VILLAGER.spawn(level,ref.bell().east(10),EntitySpawnReason.COMMAND);
  Villager unassigned=EntityTypes.VILLAGER.spawn(level,ref.bell().east(12),EntitySpawnReason.COMMAND);
  for(Villager v:List.of(qualified,novice,unassigned)){v.addTag("vp090_fixture");v.setInvulnerable(true);v.setNoAi(true);}
  qualified.setAge(-24000);novice.setAge(-24000);unassigned.setAge(-24000);
  Apprentices.plan(qualified,"minecraft:farmer");Apprentices.plan(novice,"minecraft:farmer");
  check(qualified.isBaby()&&professionPath(qualified).equals("none"),"apprentice target does not assign an adult profession to a child");
  Apprentices.awardLesson(qualified,297);Apprentices.awardLesson(qualified,3);Apprentices.awardLesson(novice,299);Apprentices.awardLesson(unassigned,1);
  check(qualified.getVillagerXp()==300&&Apprentices.get(qualified).lessonXp()==300&&Apprentices.get(novice).lessonXp()==299
   &&unassigned.getVillagerXp()==1,"teacher bonus and ordinary lessons award real XP and persist qualification XP");
  check(Apprentices.Progress.CODEC.parse(JsonOps.INSTANCE,Apprentices.Progress.CODEC.encodeStart(JsonOps.INSTANCE,Apprentices.get(qualified)).getOrThrow()).getOrThrow().equals(Apprentices.get(qualified)),
   "apprenticeship target and XP codec round trip");
  check(Apprentices.boardLabel(qualified).contains("Bauer")&&Apprentices.boardLabel(qualified).contains("300 XP"),"resident label shows apprenticeship target and progress");
  qualified.setAge(0);novice.setAge(0);unassigned.setAge(0);
  check(Apprentices.completeIfAdult(level,qualified)&&professionPath(qualified).equals("farmer")&&qualified.getVillagerData().level()==3,
   "qualified adult applies target once at rank three");
  check(Apprentices.completeIfAdult(level,novice)&&professionPath(novice).equals("farmer")&&novice.getVillagerData().level()==2,
   "unqualified adult starts target at rank two");
  var mason=net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.VILLAGER_PROFESSION,net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft","mason"));
  qualified.setVillagerData(qualified.getVillagerData().withProfession(level.registryAccess(),mason).withLevel(3));
  check(!Apprentices.completeIfAdult(level,qualified)&&professionPath(qualified).equals("mason")&&qualified.getVillagerData().level()==3,
   "completed apprenticeship does not override later profession changes");
  unassigned.setAge(0);
  check(!Apprentices.completeIfAdult(level,unassigned)&&professionPath(unassigned).equals("none"),"children without plans keep default adulthood");
  Apprentices.plan(qualified,"minecraft:farmer");Apprentices.plan(qualified,"minecraft:none");
  check(Apprentices.get(qualified).target().isEmpty()&&Apprentices.get(qualified).lessonXp()==0,"none clears profession plan and lesson progress");
  qualified.discard();novice.discard();unassigned.discard();
 }
 private static void mealChecks(ServerLevel level,VillageRef ref)throws Exception {
  Villager diner=EntityTypes.VILLAGER.spawn(level,ref.bell().south(5),EntitySpawnReason.COMMAND);diner.addTag("vp090_fixture");diner.setInvulnerable(true);diner.setNoAi(true);
  var memory=diner.getAttachedOrCreate(VillagerPlus.MEMORY);memory.lastMeal=level.getGameTime()-10000;
  Ctx ctx=new Ctx(level,diner,JobManager.state(diner),memory,ref,level.getGameTime());
  ItemStack modFood=new ItemStack(Items.BEEF);modFood.set(DataComponents.FOOD,new net.minecraft.world.food.FoodProperties(5,0.6F,false));
  check(Meals.isFood(modFood),"food component recognizes modded food items");
  SimpleContainer plainMeal=new SimpleContainer(1);plainMeal.setItem(0,new ItemStack(Items.BREAD));
  check(Meals.eatFrom(ctx,plainMeal,Meals::isFood)&&plainMeal.isEmpty(),"ordinary food without a remainder consumes safely");
  memory.lastMeal=level.getGameTime()-10000;
  diner.getInventory().setItem(0,new ItemStack(Items.COOKED_BEEF));diner.getInventory().setItem(1,new ItemStack(Items.EMERALD));
  SimpleContainer pantry=new SimpleContainer(9),till=new SimpleContainer(9);pantry.setItem(0,new ItemStack(Items.WHEAT));till.setItem(0,new ItemStack(Items.EMERALD,2));
  long oldTavernRevenue=ref.bellEntity().getAttachedOrElse(VillagerPlus.TAVERN_REVENUE,0L);
  boolean refused=(Boolean)call(Tavern.class,"serve",new Class[]{Ctx.class,Container.class},ctx,pantry);
  check(!refused&&total(pantry,Items.WHEAT)==1&&total(diner.getInventory(),Items.COOKED_BEEF)==1&&total(diner.getInventory(),Items.EMERALD)==1
   &&total(till,Items.EMERALD)==2,"tavern service rejects unprepared stock and refunds payment without consuming carried food");
  pantry.clearContent();ItemStack soup=new ItemStack(Items.MUSHROOM_STEW);
  soup.set(DataComponents.USE_REMAINDER,new net.minecraft.world.item.component.UseRemainder(net.minecraft.world.item.ItemStackTemplate.fromStack(new ItemStack(Items.BOWL))));
  pantry.setItem(0,soup);
  boolean served=(Boolean)call(Tavern.class,"serve",new Class[]{Ctx.class,Container.class},ctx,pantry);
  check(served&&total(pantry,Items.MUSHROOM_STEW)==0&&total(diner.getInventory(),Items.COOKED_BEEF)==1
   &&total(diner.getInventory(),Items.BOWL)==1,"paid tavern service consumes pantry meal only and preserves its use remainder");
  check(total(diner.getInventory(),Items.EMERALD)==0&&total(till,Items.EMERALD)==2
   &&ref.bellEntity().getAttachedOrElse(VillagerPlus.TAVERN_REVENUE,0L)==oldTavernRevenue+1,
   "successful tavern meal charges one real emerald to the persistent village revenue ledger");
  ref.bellEntity().setAttached(VillagerPlus.TAVERN_REVENUE,oldTavernRevenue);ref.dirty();
  diner.discard();
 }
 private static void railLedgerChecks(ServerLevel level,VillageRef ref,VillageRef other,Container box)throws Exception {
  Class<?> ledger=Class.forName("de.villagerplus.village.RailResources");
  Class<?>[] signature={ServerLevel.class,VillageRef.class,boolean.class,boolean.class};
  box.clearContent();box.setItem(0,new ItemStack(Items.IRON_INGOT,8));box.setItem(1,new ItemStack(Items.GOLD_INGOT,8));box.setItem(2,new ItemStack(Items.STICK,8));
  List<ItemStack> before=snapshot(box);
  check(!(Boolean)call(ledger,"canPay",signature,level,ref,false,false)&&sameContents(box,before),
   "rail ledger requires manufactured rail items instead of raw recipe ingredients");

  Class<?>[] stockSignature={ServerLevel.class,VillageRef.class};
  check(!(Boolean)call(ledger,"hasMetallurgyStock",stockSignature,level,ref),"rail metropolis unlock needs iron, gold and redstone at each endpoint");
  box.clearContent();box.setItem(0,new ItemStack(Items.IRON_INGOT,8));box.setItem(1,new ItemStack(Items.GOLD_INGOT,8));box.setItem(2,new ItemStack(Items.REDSTONE,16));
  before=snapshot(box);
  check((Boolean)call(ledger,"hasMetallurgyStock",stockSignature,level,ref)&&sameContents(box,before),"rail metallurgy prerequisite reads local stores without consuming inputs");
  box.setItem(2,new ItemStack(Items.REDSTONE,15));
  check(!(Boolean)call(ledger,"hasMetallurgyStock",stockSignature,level,ref),"rail unlock stays closed when any endpoint lacks a metallurgy input threshold");
  Class<?> roads=Class.forName("de.villagerplus.village.Roads");
  TradeNetwork oldA=Network.of(ref.bellEntity()),oldB=Network.of(other.bellEntity());
  TradeNetwork.Link ab=new TradeNetwork.Link(Network.dim(level),other.bell(),"B",90,false,0,0);
  TradeNetwork.Link ba=new TradeNetwork.Link(Network.dim(level),ref.bell(),"A",90,false,0,0);
  Network.save(ref.bellEntity(),oldA.withLinks(List.of(ab)));
  Network.save(other.bellEntity(),oldB.withLinks(List.of()));
  check(!(Boolean)call(roads,"reciprocalMetropolis",new Class[]{ServerLevel.class,VillageRef.class,VillageRef.class},level,ref,other),"a one-sided metropolis offer cannot unlock railway construction");
  Network.save(other.bellEntity(),oldB.withLinks(List.of(ba)));
  check((Boolean)call(roads,"reciprocalMetropolis",new Class[]{ServerLevel.class,VillageRef.class,VillageRef.class},level,ref,other),"railway metropolis offer requires and accepts matching approval at both endpoints");
  Network.save(ref.bellEntity(),oldA);Network.save(other.bellEntity(),oldB);
  BlockPos roadA=new BlockPos(10,64,10),roadB=new BlockPos(50,64,30);
  BlockPos forward=(BlockPos)call(roads,"routePoint",new Class[]{BlockPos.class,BlockPos.class,int.class,int.class},roadA,roadB,20,40);
  BlockPos reverse=(BlockPos)call(roads,"routePoint",new Class[]{BlockPos.class,BlockPos.class,int.class,int.class},roadB,roadA,20,40);
  check(forward.equals(reverse)&&forward.equals(new BlockPos(30,64,10)),"both road endpoints derive identical deterministic midpoint geometry");
  check((Boolean)call(roads,"alongXAt",new Class[]{BlockPos.class,BlockPos.class,int.class,int.class},roadA,roadB,0,60)
   &&!(Boolean)call(roads,"alongXAt",new Class[]{BlockPos.class,BlockPos.class,int.class,int.class},roadB,new BlockPos(30,64,70),0,70),"road lane orientation follows the dominant travel axis");

  box.clearContent();box.setItem(0,new ItemStack(Items.RAIL,2));box.setItem(1,new ItemStack(Items.BREAD,9));box.setItem(2,new ItemStack(Items.IRON_INGOT,4));
  check((Boolean)call(ledger,"canPay",signature,level,ref,false,false),"rail ledger accepts one stocked rail");
  check((Boolean)call(ledger,"pay",signature,level,ref,false,false)&&total(box,Items.RAIL)==1&&total(box,Items.BREAD)==9
   &&total(box,Items.IRON_INGOT)==4,"placing normal rail consumes exactly one rail and preserves unrelated stock");

  box.clearContent();box.setItem(0,new ItemStack(Items.POWERED_RAIL,2));box.setItem(1,new ItemStack(Items.REDSTONE,8));box.setItem(2,new ItemStack(Items.BREAD,9));
  before=snapshot(box);
  check(!(Boolean)call(ledger,"canPay",signature,level,ref,true,true)&&sameContents(box,before),
   "powered rail without redstone-block support fails without consuming rail or stock");

  box.setItem(3,new ItemStack(Items.REDSTONE_BLOCK,3));box.setItem(4,new ItemStack(Items.GOLD_INGOT,5));
  check((Boolean)call(ledger,"canPay",signature,level,ref,true,true),"powered rail and support are both funded");
  check((Boolean)call(ledger,"pay",signature,level,ref,true,true)&&total(box,Items.POWERED_RAIL)==1&&total(box,Items.REDSTONE_BLOCK)==2
   &&total(box,Items.REDSTONE)==8&&total(box,Items.BREAD)==9&&total(box,Items.GOLD_INGOT)==5,
   "placing powered rail consumes one rail plus one new support block and preserves unrelated stock");
  box.clearContent();
 }
 public static int run(CommandSourceStack source,String mode)throws Exception {
  if(!java.util.Set.of("ctov","vp-ctov-headless","vp-master-test-2026-10-29","vp-master-test-2026-10-30","vp-k04-fresh-2026-10-30","vp-k04-fresh2-2026-10-30","vp-k04-fresh3-2026-10-30","vp-k04-fresh4-2026-10-30","vp-k04-fresh5-2026-10-30","vp-k04-fresh6-2026-10-30","vp-k04-fresh7-2026-10-30","vp-k04-fresh8-2026-10-30","vp-k04-fresh9-2026-10-30","vp-k04-fresh10-2026-10-30","vp-k04-fresh11-2026-10-30","vp-k04-fresh12-2026-10-30","vp-k04-fresh13-2026-10-30","vp-k04-fresh14-2026-10-30","vp-k04-fresh15-2026-10-30","vp-k04-fresh16-2026-10-30","vp-k04-fresh17-2026-10-30","vp-k04-fresh18-2026-10-30","vp-k04-fresh19-2026-10-30","vp-k04-fresh20-2026-10-30","vp-role-test","vp-g02-live-2026-10-31","vp-wave18-2026-11-01","vp-b05-debug-2026-11-01","vp-smithsmelting-serialization-2026-11-01","vp-path-cut-2026-11-01","vp-u07-quota-2026-11-01","vp-master-wave3-2026-11-01","vp-wave27-parallel-2026-11-01","vp-wave28-featurebatch-2026-11-01","vp-wave31-featurebatch-2026-11-01","vp-wave32-featurebatch-2026-11-01","vp-wave32-featurebatch-retry-2026-11-01","vp-wave33-featurebatch-2026-11-01","vp-wave34-featurebatch-2026-11-01","vp-g03-direct-set-2026-11-01","vp-continuation-batch-2026-11-02","vp-continuation-retry-2026-11-02","vp-continuation-final-2026-11-02","vp-continuation-settled-2026-11-02","vp-nextfeaturebatch-2026-11-02","vp-nextfeaturebatch-retry-2026-11-02","vp-nextfeaturebatch-final-2026-11-02","vp-lamp-spacing-2026-11-02","vp-featurebatch-2026-11-02","vp-master-progress-2026-11-02","vp-nextfeature-clean-2026-11-02","vp-nextfeature-final-2026-11-02","vp-nextfeature-verify-2026-11-02","vp-nextfeature-verify2-2026-11-02","vp-nextfeature-verify3-2026-11-02","vp-nextfeature-verify4-2026-11-02","vp-nextfeature-verify5-2026-11-02","vp-nextfeature-verify6-2026-11-02","vp-nextfeature-verify7-2026-11-02","vp-nextfeature-verify8-2026-11-02","vp-nextfeature-verify9-2026-11-02","vp-featurecontinuation-2026-11-02","vp-feature-wave-2026-11-02","vp-feature-wave-final-2026-11-02","vp-woodland-storage-checks-2026-11-02","vp-farmstart-test-2026-11-02","vp-farmstart-diagnostic-2026-11-02","vp-farmstart-verify-2026-11-02","vp-farmstart-final-2026-11-02","vp-farmstart-final2-2026-11-02","vp-farmstart-verify3-2026-11-02","vp-featurebatch37-verified-2026-11-02","vp-featurebatch37-2026-11-02","vp-featurebatch37-final-2026-11-02","vp-featurebatch37-final2-2026-11-02","vp-featurebatch39-2026-11-03","vp-featurebatch40-2026-11-03","vp-featurebatch40-retry-2026-11-03","vp-featurebatch41-2026-11-03","vp-featurebatch41-retry-2026-11-03","vp-featurebatch41-fixes-2026-11-03","vp-featurebatch41-fixes2-2026-11-03","vp-featurebatch41-fixes3-2026-11-03","vp-featurebatch41-fixes4-2026-11-03","vp-featurebatch42-recovery-2026-11-03","vp-featurebatch42-fixes-2026-11-03","vp-eqrepair03-2026-11-03","vp-housing-grade-2026-11-03","vp-farm-storage-parallel-2026-11-03","vp-caravan-visit-follow-2026-11-03","vp-featurebundle-2026-11-04","vp-featurebundle43-retry-2026-11-04","vp-featurebundle44-home-road-projects-2026-11-04","vp-featurebundle45-farms-market-crew-2026-11-04","vp-featurebundle46-2026-11-04","vp-featurebundle46-retry-2026-11-04","vp-featurebundle47-live","vp-featurebundle48-live","vp-featurebundle49-live","vp-featurebundle50-live","vp-featurebundle51-live","vp-featurebundle52-socialcrowd","vp-featurebundle53-design-live","vp-featurebundle54-live","vp-featurebundle55-live","vp-featurebundle56-live","vp-featurebundle56-retry-live","vp-featurebundle56-final-live","vp-featurebundle56-verify-live","vp-featurebundle58-live","vp-featurebundle59-live","vp-featurebundle59-retry-live","vp-featurebundle59-final-live","vp-featurebundle59-final2-live","vp-featurebundle59-final3-live","vp-featurebundle59-final4-live","vp-featurebundle58-retry-live","vp-featurebundle58-retry2-live").contains(source.getLevel().getServer().getWorldData().getLevelName())&&!java.util.Set.of("vp-village-expansion-ui-2026-11-02","vp-cityscape-2026-11-02","vp-cityscape-final-2026-11-02","vp-cityscape-retry-2026-11-02","vp-settlement-next-2026-11-02").contains(source.getLevel().getServer().getWorldData().getLevelName()))throw new IllegalStateException("Checks only allowed in explicitly named development worlds; received: " + source.getLevel().getServer().getWorldData().getLevelName());
  ServerLevel level=source.getLevel();ServerPlayer player=level.getServer().getPlayerList().getPlayers().stream().findFirst().orElse(null);
  if(mode.equals("g02status")) {
   G02SupplyTrace.report(source,level);return 1;
  }
  if(mode.equals("g02fairness")) {
   int count=JobManagerFairnessChecks.run();
   source.sendSuccess(()->Component.literal("VP090 g02fairness PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("socialcrowd")) {
   int count=RoutineGatherChecks.run()+SocialChecks.run();
   source.sendSuccess(()->Component.literal("VP090 socialcrowd PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("railfuel")) {
   VillageRef ref=fixtureForEquipment(level,new BlockPos(29000,201,29000));
   int count=de.villagerplus.village.RailTransportFuelChecks.run(level,ref);
   source.sendSuccess(()->Component.literal("VP090 railfuel PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("gravelcycle")) {
   int count=de.villagerplus.job.GravelQuarryChecks.run(level,new BlockPos(15500,280,15500));
   source.sendSuccess(()->Component.literal("VP090 gravelcycle PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("miningcycle")) {
   int count=de.villagerplus.job.MiningCycleChecks.run(level,new BlockPos(15700,280,15700));
   source.sendSuccess(()->Component.literal("VP090 miningcycle PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("naturalprep")) {
   int count=de.villagerplus.build.NaturalSitePreparationChecks.run(level,new BlockPos(16800,280,16800));
   source.sendSuccess(()->Component.literal("VP090 naturalprep PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("stand")) {
   VillageRef standVillage=fixture(level,new BlockPos(12000,201,12000));
   int count=de.villagerplus.build.BuilderStandChecks.run(level,standVillage);
   source.sendSuccess(()->Component.literal("VP090 stand PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("palette")) {
   int count=de.villagerplus.build.PaletteChecks.run(level,new BlockPos(30000,201,30000));
   source.sendSuccess(()->Component.literal("VP090 palette PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("palettefallback")) {
   int count=de.villagerplus.build.PaletteFallbackChecks.run(level,new BlockPos(31600,201,31600));
   source.sendSuccess(()->Component.literal("VP090 palettefallback PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("buildhaul")) {
   int count=de.villagerplus.job.BuildHaulChecks.run(level,fixtureForEquipment(level,new BlockPos(30200,201,30200)));
   source.sendSuccess(()->Component.literal("VP090 buildhaul PASS "+count+" checks"),false);return count;
  }
   if(mode.equals("builderprojects")) {
    int count=de.villagerplus.build.BuilderProjectChecks.run(level,fixtureForEquipment(level,new BlockPos(30400,201,30400)));
    if(count<0) { source.sendSuccess(()->Component.literal("VP090 builderprojects PREPARING: entity-ticking fixture; retry after server ticks"),false);return -1; }
    source.sendSuccess(()->Component.literal("VP090 builderprojects PASS "+count+" checks"),false);return count;
   }
  if(mode.equals("equipment")) {
   int count=EquipmentProductionChecks.run(level,new BlockPos(14000,280,14000));
   source.sendSuccess(()->Component.literal("VP090 equipment PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("developmentwall")) {
   int count=DevelopmentChecks.wallStructureDiscoveryChecks();
   source.sendSuccess(()->Component.literal("VP090 developmentwall PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("recipecrafting")) {
   int count=de.villagerplus.job.RecipeCraftingChecks.run(level);
   count+=de.villagerplus.job.WorkshopSupplyChecks.run(level,fixtureForEquipment(level,new BlockPos(31200,201,31200)));
   int total=count;
   source.sendSuccess(()->Component.literal("VP090 recipecrafting PASS "+total+" checks"),false);return total;
  }
  if(mode.equals("projectvisibility")) {
   int count=DevelopmentChecks.constructionProjectVisibilityChecks(level,fixture(level,new BlockPos(30600,201,30600)));
   source.sendSuccess(()->Component.literal("VP090 projectvisibility PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("school")) {
   int count=de.villagerplus.job.SchoolChecks.run(level,fixture(level,new BlockPos(13200,201,13200)));
   source.sendSuccess(()->Component.literal("VP090 school PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("charcoal")) {
   var ref=fixture(level,new BlockPos(13400,201,13400));
   int count=de.villagerplus.job.CharcoalChecks.run(level,ref);
   count+=de.villagerplus.job.LightingSpacingChecks.charcoalTorchChecks(level,ref,ref.bell());
   int total=count;
   source.sendSuccess(()->Component.literal("VP090 charcoal PASS "+total+" checks"),false);return total;
  }
  if(mode.equals("mining")) {
   int count=MiningSafetyChecks.run(level,new BlockPos(13000,310,13000));
   source.sendSuccess(()->Component.literal("VP090 mining PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("g01spacing")) {
   int count=de.villagerplus.village.G01VillageSpacingChecks.run();
   source.sendSuccess(()->Component.literal("VP090 g01spacing PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("a01behavior")) {
   int count=de.villagerplus.job.AntiStuckChecks.run(level,fixture(level,new BlockPos(30400,201,30400)));
   source.sendSuccess(()->Component.literal("VP090 a01behavior PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("minetickets")) {
   int count=de.villagerplus.village.AnchorMineTicketChecks.check();
   source.sendSuccess(()->Component.literal("VP090 minetickets PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("stationshed")) {
   int count=de.villagerplus.build.StationShedChecks.run(level,new BlockPos(28200,201,28200));
   source.sendSuccess(()->Component.literal("VP090 stationshed PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("craftrecipes")) {
   int count=de.villagerplus.job.CraftRecipeChecks.run(level,new BlockPos(29000,201,29000));
   source.sendSuccess(()->Component.literal("VP090 craftrecipes PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("wellbeing")) {
   int count=de.villagerplus.village.WellbeingChecks.run(level,new BlockPos(29400,201,29400));
   source.sendSuccess(()->Component.literal("VP090 wellbeing PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("roads")) {
   int count=de.villagerplus.village.RoadTerrainChecks.run(level,A.offset(420,0,420));
   count+=de.villagerplus.build.BuilderPathChecks.run(level,A.offset(460,0,460));
   int checks=count;
   source.sendSuccess(()->Component.literal("VP090 roads PASS "+checks+" checks"),false);return checks;
  }
  if(mode.equals("builderpaths")) {
   int count=de.villagerplus.build.BuilderPathChecks.run(level,A.offset(460,0,460));
   source.sendSuccess(()->Component.literal("VP090 builderpaths PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("roadterrain")) {
   int count=de.villagerplus.village.RoadTerrainChecks.run(level,A.offset(420,0,420));
   source.sendSuccess(()->Component.literal("VP090 roadterrain PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("roadgrading")) {
   int count=de.villagerplus.village.RoadGradingChecks.run(level,new BlockPos(28700,201,28700));
   source.sendSuccess(()->Component.literal("VP090 roadgrading PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("roadwidth")) {
   int count=de.villagerplus.village.RoadWidthChecks.run();
   source.sendSuccess(()->Component.literal("VP090 roadwidth PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("houseplot")) {
   BlockPos origin=new BlockPos(19000,201,19000);
   int count=de.villagerplus.village.HousePlotChecks.run(level,origin);
   Villager worker=EntityTypes.VILLAGER.spawn(level,origin.east(2),EntitySpawnReason.COMMAND);
   if(worker==null)throw new IllegalStateException("House lot fixture: could not spawn a builder");
   worker.addTag("vp090_fixture");worker.setNoAi(true);worker.setInvulnerable(true);
   try {count+=de.villagerplus.build.HouseLotPreparationChecks.run(level,origin,worker);}
   finally {worker.discard();}
   int checks=count;
   source.sendSuccess(()->Component.literal("VP090 houseplot PASS "+checks+" checks"),false);return checks;
  }
  if(mode.equals("housesitesearch")) {
   int count=de.villagerplus.build.HouseSiteSearchChecks.run(level,new BlockPos(36000,201,36000));
   source.sendSuccess(()->Component.literal("VP090 housesitesearch PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("houseconnection")) {
   int count=de.villagerplus.build.HouseConnectionChecks.run(level,new BlockPos(24200,201,24200));
   source.sendSuccess(()->Component.literal("VP090 houseconnection PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("lightspacing")) {
   int count=de.villagerplus.job.LightingSpacingChecks.run(level,new BlockPos(15500,201,15500));
   source.sendSuccess(()->Component.literal("VP090 lightspacing PASS "+count+" checks"),false);return count;
  }
   if(mode.equals("wallconstruction")) {
    VillageRef ref=fixture(level,new BlockPos(32000,201,32000));
    int count=de.villagerplus.build.WallConstructionChecks.run(level,ref);
    source.sendSuccess(()->Component.literal("VP090 wallconstruction PASS "+count+" checks"),false);return count;
   }
  if(mode.equals("u07quota")) {
   var ref=fixture(level,new BlockPos(21800,201,21800));
   int count=de.villagerplus.village.U07QuotaChecks.run(level,ref);
   if(count<0) { source.sendSuccess(()->Component.literal("VP090 u07quota PREPARING entity fixture chunk; no checks executed yet"),false);return -1; }
   source.sendSuccess(()->Component.literal("VP090 u07quota PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("privatechest")) {
   var ref=fixtureForEquipment(level,new BlockPos(19600,201,19600));
   Villager tester=EntityTypes.VILLAGER.spawn(level,ref.bell().east(6),EntitySpawnReason.COMMAND);tester.addTag("vp090_fixture");tester.setNoAi(true);
   int count;
   try {
    count=de.villagerplus.village.PrivateChestProvisioningChecks.run(level,ref,tester);
    count+=de.villagerplus.job.FletcherChestChecks.run(level);
   } finally {tester.discard();}
   int totalCount=count;
   source.sendSuccess(()->Component.literal("VP090 privatechest PASS "+totalCount+" checks"),false);return totalCount;
  }
  if(mode.equals("zonepolicy")) {
   int count=ZonePolicyChecks.run(fixture(level,new BlockPos(19800,201,19800)));
   source.sendSuccess(()->Component.literal("VP090 zonepolicy PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("zoneexpansion")) {
   int count=de.villagerplus.build.ConstructionZoneChecks.run(level,fixture(level,new BlockPos(19800,201,19800)));
   source.sendSuccess(()->Component.literal("VP090 zoneexpansion PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("stockpolicy")) {
   var ref=fixtureForEquipment(level,new BlockPos(19600,201,19600));
   Villager tester=EntityTypes.VILLAGER.spawn(level,ref.bell().east(6),EntitySpawnReason.COMMAND);tester.addTag("vp090_fixture");tester.setNoAi(true);
   int count;
    try {
     count=StockPolicyChecks.run(level,ref,tester);
     count+=de.villagerplus.dev.StockPolicyDirectWriteChecks.run(level,ref);
     if(net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("farmersdelight"))
      count+=de.villagerplus.village.StockPolicyFoodChecks.run(level);
    } finally {tester.discard();}
    int totalCount=count+de.villagerplus.village.LagerOwnershipChecks.run(level,ref,ref.bell().north(14));
    source.sendSuccess(()->Component.literal("VP090 stockpolicy PASS "+totalCount+" checks"),false);return totalCount;
  }
  if(mode.equals("tavernservice")) {
   var ref=fixtureForEquipment(level,new BlockPos(19800,201,19800));
   Villager tester=EntityTypes.VILLAGER.spawn(level,ref.bell().east(6),EntitySpawnReason.COMMAND);tester.addTag("vp090_fixture");tester.setNoAi(true);
   int count;
   try {count=de.villagerplus.village.TavernChecks.run(level,ref,tester);} finally {tester.discard();}
   source.sendSuccess(()->Component.literal("VP090 tavernservice PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("roadroute")) {
   int count=de.villagerplus.village.RoadRouteChecks.run(level,new BlockPos(23000,280,23000));
   source.sendSuccess(()->Component.literal("VP090 roadroute PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("roadwork")) {
   int count=de.villagerplus.job.RoadMaintenanceChecks.run(level,new BlockPos(15000,201,15000));
   source.sendSuccess(()->Component.literal("VP090 roadwork PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("railwork")) {
   int count=de.villagerplus.village.RailMaintenanceChecks.run(level,new BlockPos(16000,201,16000));
   source.sendSuccess(()->Component.literal("VP090 railwork PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("roadclear")) {
   int count=de.villagerplus.job.RoadClearingChecks.run(level,new BlockPos(16500,201,16500));
   source.sendSuccess(()->Component.literal("VP090 roadclear PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("smithrail")) {
   int count=de.villagerplus.job.SmithRailCraftChecks.run(level,new BlockPos(14500,280,14500));
   source.sendSuccess(()->Component.literal("VP090 smithrail PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("milestones")) {
   var ref=fixtureForEquipment(level,A);
   int count=de.villagerplus.build.MilestoneWaitingChecks.run(level,ref);
   source.sendSuccess(()->Component.literal("VP090 milestones PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("minewalk")) {
   int count=de.villagerplus.job.MineWalkChecks.run(level,new BlockPos(43000,201,43000));
   source.sendSuccess(()->Component.literal("VP090 minewalk PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("minekit")) {
   BlockPos at=new BlockPos(47000,201,47000);
   BlockPos at2=new BlockPos(47300,201,47000);
   int count=de.villagerplus.job.MineKitChecks.run(level,fixtureForEquipment(level,at),at)
    +de.villagerplus.job.MineKitChecks.runDelivery(level,fixtureForEquipment(level,at2),at2);
   source.sendSuccess(()->Component.literal("VP090 minekit PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("treewalkback")) {
   int count=de.villagerplus.job.TreeWalkbackChecks.run(level,new BlockPos(46000,201,46000));
   source.sendSuccess(()->Component.literal("VP090 treewalkback PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("housesign")) {
   int count=de.villagerplus.village.HousingSignChecks.run(level,new BlockPos(47000,201,47000));
   source.sendSuccess(()->Component.literal("VP090 housesign PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("strandrescue")) {
   int count=de.villagerplus.job.StrandRescueChecks.run(level,new BlockPos(41000,201,41000));
   source.sendSuccess(()->Component.literal("VP090 strandrescue PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("freightload")) {
   int count=de.villagerplus.job.FreightLoadingChecks.run(level,new BlockPos(49000,201,49000));
   source.sendSuccess(()->Component.literal("VP090 freightload PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("lamplighter")) {
   int count=de.villagerplus.job.LamplighterJobChecks.run(level,new BlockPos(51008,201,51008));
   source.sendSuccess(()->Component.literal("VP090 lamplighter PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("butcherslaughter")) {
   int count=de.villagerplus.job.ButcherSlaughterChecks.run(level,new BlockPos(50400,201,50400));
   if(count<0) { source.sendSuccess(()->Component.literal("VP090 butcherslaughter PREPARING entity-ticking fixture; retry after server ticks"),false);return -1; }
   source.sendSuccess(()->Component.literal("VP090 butcherslaughter PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("deepstrand")) {
   int count=de.villagerplus.job.DeepStrandChecks.run(level,new BlockPos(47500,201,47500));
   source.sendSuccess(()->Component.literal("VP090 deepstrand PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("farmexpand")) {
   var ref=fixtureForEquipment(level,A);
   int count=de.villagerplus.job.FarmerExpansionChecks.run(level,ref);
   count+=de.villagerplus.job.FarmerSeedGrassChecks.run(level,ref);
   count+=de.villagerplus.build.FarmPlanSizingChecks.run();
   int total=count;
   source.sendSuccess(()->Component.literal("VP090 farmexpand PASS "+total+" checks"),false);return total;
  }
  if(mode.equals("livestockherding")) {
   var ref=fixtureForEquipment(level,new BlockPos(27000,201,27000));
   int count=de.villagerplus.job.LivestockHerdingChecks.run(level,ref);
   if(count<0) { source.sendSuccess(()->Component.literal("VP090 livestockherding PREPARING entity-ticking fixture; retry after server ticks"),false);return -1; }
   source.sendSuccess(()->Component.literal("VP090 livestockherding PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("railtransport")) {
   int count=de.villagerplus.village.RailTransportChecks.run();
   source.sendSuccess(()->Component.literal("VP090 railtransport PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("ctov")) {
   var natural=CtovHeadlessChecks.bootstrap(level);
   int count=CtovMilestoneChecks.runWalk(level,natural);
   count+=CtovMilestoneChecks.runHarness(level,natural);
   count+=CtovLifeChecks.run(level,natural);
   count+=CtovHeadlessChecks.run(level,natural);
   count+=CompatChecks.runStrict(level);
   count+=de.villagerplus.job.CookDelightChecks.run(level,natural.bell().offset(200,0,200));
   int caravanChecks=de.villagerplus.village.CaravanPorterDeliveryChecks.run(level,new BlockPos(14000,201,14000));
   if(caravanChecks<0) {
    source.sendSuccess(()->Component.literal("VP090 ctov PREPARING: Caravan entity-ticking fixture; retry after a few server ticks"),false);return 0;
   }
   count+=caravanChecks;
   count+=de.villagerplus.village.RailEndToEndChecks.run(level,new BlockPos(13000,201,13000));
   int total=count;
   source.sendSuccess(()->Component.literal("VP090 ctov PASS "+total+" checks"),false);return count;
  }
  if(mode.equals("ctov-life")) {
   var natural=CtovHeadlessChecks.bootstrap(level);
   int count=CtovLifeChecks.run(level,natural);
   source.sendSuccess(()->Component.literal("VP090 ctov-life PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("ctov-verify")) {
   int count=CtovHeadlessChecks.verify(level);
   source.sendSuccess(()->Component.literal("VP090 ctov-verify PASS "+count),false);return count;
  }
  if(mode.equals("railmetro")) {
   int count=de.villagerplus.village.RoadMetropolisChecks.run(level,new BlockPos(17000,201,17000));
   count+=de.villagerplus.village.RailEndToEndChecks.run(level,new BlockPos(13000,201,13000));
   int total=count;
   source.sendSuccess(()->Component.literal("VP090 railmetro PASS "+total+" checks"),false);return total;
  }
  if(mode.equals("caravandelivery")) {
   int count=de.villagerplus.village.CaravanPorterDeliveryChecks.run(level,new BlockPos(14000,201,14000));
   if(count<0) {
   source.sendSuccess(()->Component.literal("VP090 caravandelivery PREPARING: entity-ticking fixture; retry after a few server ticks"),false);return -1;
   }
   source.sendSuccess(()->Component.literal("VP090 caravandelivery PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("marketplan")) {
   BlockPos at=new BlockPos(19400,201,19400);
   var ref=fixtureForEquipment(level,at);
   int count=de.villagerplus.village.MarketPlanningChecks.run(level,ref,at.east(24));
   count+=de.villagerplus.village.MarketSiteRecoveryChecks.run(level,ref,at.east(48));
   int total=count;
   source.sendSuccess(()->Component.literal("VP090 marketplan PASS "+total+" checks"),false);return total;
  }
  if(mode.equals("marketprep")) {
   BlockPos at=new BlockPos(25400,201,25000);
   fixtureForEquipment(level,at);
   int count=de.villagerplus.village.MarketSitePreparationChecks.run(level,at.east(24));
   source.sendSuccess(()->Component.literal("VP090 marketprep PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("statusformat")) {
   int count=de.villagerplus.village.VillageStatusFormatChecks.run();
   source.sendSuccess(()->Component.literal("VP090 statusformat PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("marketlifecycle")) {
   BlockPos at=new BlockPos(19200,201,19200);
   var ref=fixtureForEquipment(level,at);
   long oldOverworldClock=level.getOverworldClockTime();
   int count;
   try {
    level.getServer().getCommands().performPrefixedCommand(source,"time set 5000");
    count=de.villagerplus.village.MarketLifecycleChecks.run(level,ref,at.east(5));
   } finally {
    level.getServer().getCommands().performPrefixedCommand(source,"time set "+oldOverworldClock);
   }
   if(count<0)return -1;
   source.sendSuccess(()->Component.literal("VP090 marketlifecycle PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("a04survival")) {
   int count=de.villagerplus.village.A04SurvivalChecks.run();
   source.sendSuccess(()->Component.literal("VP090 a04survival PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("featurebundle54")) {
   int count=de.villagerplus.village.NetworkDiscoveryChecks.run();
   count+=de.villagerplus.village.NetworkNeighborChecks.run();
   count+=de.villagerplus.job.SocialChecks.run();
   count+=de.villagerplus.build.SupplyChecks.run();
   count+=de.villagerplus.build.BuilderChecks.run();

   BlockPos privateAt=new BlockPos(19600,201,19600);
   VillageRef privateRef=fixtureForEquipment(level,privateAt);
   Villager privateTester=EntityTypes.VILLAGER.spawn(level,privateRef.bell().east(6),EntitySpawnReason.COMMAND);
   privateTester.addTag("vp090_fixture"); privateTester.setNoAi(true);
   try { count+=de.villagerplus.village.PrivateChestProvisioningChecks.run(level,privateRef,privateTester); }
   finally { privateTester.discard(); }

   BlockPos marketAt=new BlockPos(25400,201,25000);
   fixtureForEquipment(level,marketAt);
   count+=de.villagerplus.village.MarketSitePreparationChecks.run(level,marketAt.east(24));

   BlockPos expansionAt=new BlockPos(19000,201,19000);
   count+=de.villagerplus.village.HousePlotChecks.run(level,expansionAt);
   Villager expansionWorker=EntityTypes.VILLAGER.spawn(level,expansionAt.east(2),EntitySpawnReason.COMMAND);
   if(expansionWorker==null) throw new IllegalStateException("Feature bundle 54: could not spawn a house-lot worker");
   expansionWorker.addTag("vp090_fixture"); expansionWorker.setNoAi(true); expansionWorker.setInvulnerable(true);
   try { count+=de.villagerplus.build.HouseLotPreparationChecks.run(level,expansionAt,expansionWorker); }
   finally { expansionWorker.discard(); }

   count+=de.villagerplus.job.MineLifecycleChecks.run(level,new BlockPos(24000,280,24000));
   int total=count;
   source.sendSuccess(()->Component.literal("VP090 featurebundle54 PASS "+total+" checks"),false);return total;
  }
  if(mode.equals("featurebundle55")) {
   int freight=de.villagerplus.village.RailTransportDispatchChecks.run(level,new BlockPos(25200,280,25200));
   if(freight<0)return -1;
   int count=freight;
   count+=run(source,"plazageometry");
   count+=run(source,"caravandeparture");
   int total=count;
   source.sendSuccess(()->Component.literal("VP090 featurebundle55 PASS "+total+" checks"),false);return total;
  }
  if(mode.equals("networkdiscovery")) {
   int count=de.villagerplus.village.NetworkDiscoveryChecks.run();
   source.sendSuccess(()->Component.literal("VP090 networkdiscovery PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("renamevillage")) {
   var ref=fixtureForEquipment(level,new BlockPos(18400,201,18400));
   int count=de.villagerplus.net.RenameVillageChecks.run(level,ref);
   source.sendSuccess(()->Component.literal("VP090 renamevillage PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("kitchenfurnisher")) {
   int count=de.villagerplus.build.KitchenFurnisherChecks.run(level,new BlockPos(18600,201,18600));
   source.sendSuccess(()->Component.literal("VP090 kitchenfurnisher PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("constructionsite")) {
   BlockPos at=new BlockPos(27000,201,27000);
   var ref=fixtureForEquipment(level,at);
   int count=de.villagerplus.build.ConstructionSiteChecks.run(level,ref);
   source.sendSuccess(()->Component.literal("VP090 constructionsite PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("cookdelight")) {
   int count=de.villagerplus.job.CookDelightChecks.run(level,new BlockPos(13500,201,13500));
   count+=de.villagerplus.job.DelightKitchenCraftingChecks.run(level,new BlockPos(13700,201,13700));
   int resultCount=count;
   source.sendSuccess(()->Component.literal("VP090 cookdelight PASS "+resultCount+" checks"),false);return count;
  }
  if(mode.equals("mealcycle")) {
   int count=de.villagerplus.job.HouseholdMealChecks.run(level,new BlockPos(28200,201,28200));
   source.sendSuccess(()->Component.literal("VP090 mealcycle PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("tavernmeals")) {
   int count=de.villagerplus.village.TavernMealFlowChecks.run(level,new BlockPos(28400,201,28400));
   source.sendSuccess(()->Component.literal("VP090 tavernmeals PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("planstonesupply")) {
   int count=de.villagerplus.job.PlanStoneSupplyChecks.run(level);
   source.sendSuccess(()->Component.literal("VP090 planstonesupply PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("surfacestone")) {
   BlockPos at=new BlockPos(33000,201,33000);
   BlockPos metalAt=new BlockPos(33300,201,33000);
   int count=de.villagerplus.job.SurfaceStoneChecks.run(level,fixtureForEquipment(level,at),at)
    +de.villagerplus.job.SurfaceStoneChecks.runMetalPickaxe(level,fixtureForEquipment(level,metalAt),metalAt);
   source.sendSuccess(()->Component.literal("VP090 surfacestone PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("planmaterialwait")) {
   int count=de.villagerplus.build.PlanMaterialWaitChecks.run();
   source.sendSuccess(()->Component.literal("VP090 planmaterialwait PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("a03weather")) {
   int count=de.villagerplus.job.A03WeatherChecks.run(level,new BlockPos(17600,201,17600));
   source.sendSuccess(()->Component.literal("VP090 a03weather PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("villagerinfo")) {
   int count=de.villagerplus.village.VillagerInfoChecks.run(level,player,new BlockPos(29200,201,29200));
   source.sendSuccess(()->Component.literal("VP090 villagerinfo PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("villagerename")) {
   int count=de.villagerplus.village.VillageRenameChecks.run();
   source.sendSuccess(()->Component.literal("VP090 villagerename PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("butcher")) {
   var ref=fixtureForEquipment(level,new BlockPos(17800,201,17800));
   int count=de.villagerplus.job.ButcherChecks.run(level,ref);
   if(count<0) { source.sendSuccess(()->Component.literal("VP090 butcher PREPARING entity-ticking fixture; retry after server ticks"),false);return -1; }
   source.sendSuccess(()->Component.literal("VP090 butcher PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("healer")) {
   var ref=fixtureForEquipment(level,new BlockPos(18000,201,18000));
   int count=de.villagerplus.job.HealerCareChecks.run(level,ref);
   source.sendSuccess(()->Component.literal("VP090 healer PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("roadlighting")) {
   int count=de.villagerplus.job.RoadLightingChecks.run(level,new BlockPos(18200,201,18200));
   source.sendSuccess(()->Component.literal("VP090 roadlighting PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("smithanvil")) {
   int count=de.villagerplus.job.SmithAnvilChecks.run(level,new BlockPos(18800,201,18800))
     +de.villagerplus.job.EquipmentMaintenanceChecks.run(level,new BlockPos(12400,201,12000));
   source.sendSuccess(()->Component.literal("VP090 smithanvil PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("thermalrecipes")) {
   VillageRef ref=fixtureForEquipment(level,new BlockPos(18400,201,18400));
   int count=de.villagerplus.job.ThermalRecipeChecks.run(level,ref);
   source.sendSuccess(()->Component.literal("VP090 thermalrecipes PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("smithsmelting")) {
   VillageRef ref=fixtureForEquipment(level,new BlockPos(18600,201,18600));
   int count=de.villagerplus.job.SmithSmeltingChecks.run(level,ref);
   source.sendSuccess(()->Component.literal("VP090 smithsmelting PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("supply")) {
   int count=de.villagerplus.build.SupplyChecks.run();
   source.sendSuccess(()->Component.literal("VP090 supply PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("demolition")) {
   VillageRef ref=fixtureForEquipment(level,new BlockPos(12000,201,12000));
   int count=de.villagerplus.village.DemolitionWorldChecks.run(level,ref);
   if(count<0) { source.sendSuccess(()->Component.literal("VP090 demolition PREPARING: entity-ticking fixture; retry after server ticks"),false);return -1; }
   source.sendSuccess(()->Component.literal("VP090 demolition PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("buildingrelocation")) {
   VillageRef ref=fixtureForEquipment(level,new BlockPos(27800,201,27800));
   int count=de.villagerplus.village.BuildingRelocationWorldChecks.run(level,ref);
   source.sendSuccess(()->Component.literal("VP090 buildingrelocation PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("compat")) {
   int count=CompatChecks.runStrict(level);
   source.sendSuccess(()->Component.literal("VP090 compat PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("woodcutting")) {
   int count=de.villagerplus.job.WoodcuttingCycleChecks.run();
   source.sendSuccess(()->Component.literal("VP090 woodcutting PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("woodcuttinggroup")) {
   int count=de.villagerplus.job.WoodcuttingGroupChecks.run(level,new BlockPos(25000,201,25000))
    +de.villagerplus.job.WoodcuttingCycleChecks.run();
   source.sendSuccess(()->Component.literal("VP090 woodcuttinggroup PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("woodcutprepare")||mode.equals("woodcutverify")) {
   int count=mode.equals("woodcutprepare")?de.villagerplus.job.WoodcuttingRestartChecks.prepare(level,new BlockPos(21000,280,21000))
    :de.villagerplus.job.WoodcuttingRestartChecks.verify(level,new BlockPos(21000,280,21000));
   source.sendSuccess(()->Component.literal("VP090 "+mode+" PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("roadclearprepare")||mode.equals("roadclearverify")) {
   int count=mode.equals("roadclearprepare")?de.villagerplus.job.RoadClearingRestartChecks.prepare(level,new BlockPos(20000,280,20000))
    :de.villagerplus.job.RoadClearingRestartChecks.verify(level,new BlockPos(20000,280,20000));
   source.sendSuccess(()->Component.literal("VP090 "+mode+" PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("caravansites")) {
   int count=de.villagerplus.village.CaravanSiteChecks.run(level,new BlockPos(18000,280,18000));
   count+=de.villagerplus.village.CaravanCampChecks.run();
   count+=de.villagerplus.village.CaravanCampChecks.runWorld(level,new BlockPos(18000,280,18000));
   int checks=count;
   source.sendSuccess(()->Component.literal("VP090 caravansites PASS "+checks+" checks"),false);return checks;
  }
  if(mode.equals("caravanr02")) {
   int count=de.villagerplus.village.CaravanR02Checks.runWorld(level,new BlockPos(0,201,0));
   source.sendSuccess(()->Component.literal("VP090 caravanr02 PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("caravandeparture")) {
   int count=de.villagerplus.village.CaravanDepartureChecks.run(level,new BlockPos(27200,201,27200));
   source.sendSuccess(()->Component.literal("VP090 caravandeparture PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("minelifecycle")) {
   int count=de.villagerplus.job.MineLifecycleChecks.run(level,new BlockPos(24000,280,24000));
   source.sendSuccess(()->Component.literal("VP090 minelifecycle PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("caravanroad")) {
   BlockPos at=new BlockPos(22000,280,22000);
   for(int x=-16;x<=48;x+=16)for(int z=-16;z<=48;z+=16)level.getChunkAt(at.offset(x,0,z));
   int count=de.villagerplus.village.CaravanRoadChecks.run(level,at);
   source.sendSuccess(()->Component.literal("VP090 caravanroad PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("roadbuild")) {
   int count=de.villagerplus.village.RoadPathGapChecks.run(level,new BlockPos(16000,280,16000).offset(128,0,128));
   count+=de.villagerplus.job.RoadConstructionChecks.run(level,new BlockPos(16000,280,16000));
   int checks=count;
   source.sendSuccess(()->Component.literal("VP090 roadbuild PASS "+checks+" checks"),false);return checks;
  }
  if(mode.equals("roadbridge")||mode.equals("roadbridgeverify")) {
   int count=mode.equals("roadbridge")?de.villagerplus.job.RoadBridgeChecks.run(level,new BlockPos(17000,280,17000))
    :de.villagerplus.job.RoadBridgeChecks.verify(level,new BlockPos(17000,280,17000));
   source.sendSuccess(()->Component.literal("VP090 "+mode+" PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("houses")) {
   int count=de.villagerplus.build.HouseDesignCatalogChecks.run();
   count+=de.villagerplus.build.JobHutAppearanceChecks.run();
   count+=de.villagerplus.build.TemplateLibraryChecks.run();
   count+=de.villagerplus.build.HouseTemplateChecks.run(level,A.offset(440,0,440));
   int reportedCount=count;
   source.sendSuccess(()->Component.literal("VP090 houses PASS "+reportedCount+" checks"),false);return count;
  }
  if(mode.equals("tafelgross")) {
   VillageRef ref=fixture(level,A);
   int count=de.villagerplus.village.DorftafelGrossChecks.run(level,A.offset(60,0,60),ref);
   source.sendSuccess(()->Component.literal("VP090 tafelgross PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("templatebuild")) {
   VillageRef ref=fixture(level,A);
   int count=de.villagerplus.build.TemplateLibraryChecks.runBuild(level,ref);
   source.sendSuccess(()->Component.literal("VP090 templatebuild PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("civic")) {
   VillageRef ref=fixture(level,A);
   int count=de.villagerplus.village.CivicCenterChecks.run(level,ref);
   source.sendSuccess(()->Component.literal("VP090 civic PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("plazageometry")) {
   int count=de.villagerplus.village.PlazaChecks.run();
   count+=de.villagerplus.village.PlazaGeometryChecks.run();
   count+=de.villagerplus.village.PlazaFountainChecks.run(level,new BlockPos(28000,300,28000));
   int reportedCount=count;
   source.sendSuccess(()->Component.literal("VP090 plazageometry PASS "+reportedCount+" checks"),false);return count;
  }
  if(mode.equals("roles")) {
   int roleCycleCount=RoleCycleChecks.run(level,new BlockPos(26000,300,26000));
   if(roleCycleCount<0) { source.sendSuccess(()->Component.literal("VP090 roles PREPARING entity-ticking fixture; retry after server ticks"),false);return -1; }
   int count=roleCycleCount;
   count+=de.villagerplus.job.AnimChecks.run(level,fixtureForEquipment(level,new BlockPos(27600,201,27600)));
   int resultCount=count;
   source.sendSuccess(()->Component.literal("VP090 roles PASS "+resultCount+" checks"),false);return count;
  }
  if(mode.equals("rolevisual")) {
   int count=de.villagerplus.job.AnimChecks.run(level,fixtureForEquipment(level,new BlockPos(27600,201,27600)));
   source.sendSuccess(()->Component.literal("VP090 rolevisual PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("neighbors")) {
   int count=de.villagerplus.village.NetworkNeighborChecks.run();
   source.sendSuccess(()->Component.literal("VP090 neighbors PASS "+count+" checks"),false);return count;
  }
  if(mode.equals("villagelookup")) {
   int count=de.villagerplus.village.VillageLookupChecks.run(level,new BlockPos(0,201,0));
   source.sendSuccess(()->Component.literal("VP090 villagelookup PASS "+count+" checks"),false);return count;
  }
  if(player==null){source.sendFailure(Component.literal("Connect the development client first."));return 0;}
  if(mode.equals("plaza")) {
   var ref=Villages.villageNear(level,player.blockPosition(),128);if(ref==null)return 0;
   var hint=level.getEntitiesOfClass(Villager.class,new AABB(ref.bell()).inflate(96)).stream().findFirst().orElse(null);if(hint==null)return 0;
   var m=Market.of(ref);Market.save(ref,new MarketData(m.plaza(),m.shops(),0,m.sales()));Plaza.ensure(level,ref,hint,level.getGameTime());
   source.sendSuccess(()->Component.literal("Plaza "+Market.of(ref).plaza()),false);return Market.of(ref).plaza().isPresent()?1:0;
  }
  if(mode.equals("village")) {
   var ref=Villages.villageNear(level,player.blockPosition(),128);if(ref==null)return 0;
   source.sendSuccess(()->Component.literal("Bell "+ref.bell()+" chest "+ref.data().chest+" plaza "+Market.of(ref).plaza()),false);return 1;
  }
  if(mode.equals("toast")) {
   de.villagerplus.village.VillageMessages.system(player,Component.literal("Die Dorfverwaltung hat eine Nachricht für dich vorbereitet."));return 1;
  }
  if(mode.equals("board")) {
   var ref=Villages.villageNear(level,player.blockPosition(),96);if(ref==null)return 0;
   de.villagerplus.block.DorftafelBlock.open(level,player,ref.data().extra.board.orElse(ref.bell()));return 1;
  }
  if(mode.equals("trader")) {
   var traders=level.getEntitiesOfClass(de.villagerplus.entity.CaravanTraderEntity.class,new AABB(player.blockPosition()).inflate(120));if(traders.isEmpty())return 0;
   var trader=traders.get(0);player.teleportTo(trader.getX()+2,trader.getY(),trader.getZ());trader.interact(player,InteractionHand.MAIN_HAND,net.minecraft.world.phys.Vec3.ZERO);return 1;
  }
  if(mode.equals("verify")){
   // The test plateau is remote from spawn; load the complete persisted fixture footprint before querying its entities.
   for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)level.getChunk(A.offset(dx*16,0,dz*16));
   var be=level.getBlockEntity(A);check(be!=null,"persisted test bell");var ref=new VillageRef(A,be.getAttachedOrCreate(VillagerPlus.VILLAGE),be);
   int cargoChunkX=(A.getX()+30)>>4,cargoChunkZ=(A.getZ()+30)>>4;
   level.setChunkForced(cargoChunkX,cargoChunkZ,true);
   level.getChunk(new BlockPos(A.getX()+30,A.getY(),A.getZ()+30));
   var recovered=level.getEntitiesOfClass(net.minecraft.world.entity.animal.equine.Donkey.class,new AABB(A).inflate(100),d->d.entityTags().contains("vp090_fixture")&&d.getCustomName()!=null
    &&d.getCustomName().getString().equals("Karawanen-Frachtesel")&&d.getSlot(502)!=null&&d.getSlot(502).get().is(Items.IRON_INGOT));
   String donkeyState=level.getEntitiesOfClass(net.minecraft.world.entity.animal.equine.Donkey.class,new AABB(A).inflate(100),d->d.entityTags().contains("vp090_fixture"))
    .stream().map(d->d.blockPosition()+" name="+d.getCustomName()+" persistent="+d.isPersistenceRequired()+" tags="+d.entityTags()+" slot502="+(d.getSlot(502)==null?"null":d.getSlot(502).get())).toList().toString();
   try {
    check(recovered.size()==1&&recovered.get(0).getSlot(502).get().getCount()==7&&recovered.get(0).isPersistenceRequired(),
     "orphaned caravan donkey keeps its exact cargo through a real server restart; recovered="+recovered.size()+" candidates="+donkeyState);
     if(recovered.size()==1){
      var display=recovered.get(0).getPassengers().stream().filter(net.minecraft.world.entity.Display.ItemDisplay.class::isInstance)
       .map(net.minecraft.world.entity.Display.ItemDisplay.class::cast).toList();
      String markerState=display.stream().map(e->"uuid="+e.getUUID()+" vehicle="+(e.getVehicle()==null?"null":e.getVehicle().getUUID())
       +" item="+e.getItemStack()+" tags="+e.entityTags()).toList().toString();
      String ownerTag="vp_caravan_cargo_owner:"+recovered.get(0).getUUID();
      check(display.size()==1&&display.get(0).getVehicle()==recovered.get(0)&&display.get(0).getItemStack().is(Items.IRON_INGOT)
       &&display.get(0).entityTags().contains(ownerTag),
       "cargo marker remains attached to its exact donkey after reload; passengers="+display.size()+" state="+markerState);}
   } finally {
    level.setChunkForced(cargoChunkX,cargoChunkZ,false);
   }
   ConstructionSite savedConstruction=be.getAttachedOrElse(VillagerPlus.CONSTRUCTION,null);
   check(savedConstruction!=null&&savedConstruction.prepared()&&savedConstruction.phaseEnds().size()==5,"construction phase metadata survives restart");
   be.removeAttached(VillagerPlus.CONSTRUCTION);ref.data().planType="";ref.data().planOrigin=Optional.empty();ref.data().wanted.clear();ref.dirty();
   check(Network.of(be).links().stream().anyMatch(l->l.bell().equals(B)),"network links survive restart");
   check(Network.treasuryEmeralds(ref)==5,"caravan cash delivery and later spending survive a real server restart; balance="+Network.treasuryEmeralds(ref));
   check(Market.of(ref).plaza().isPresent(),"plaza survives restart");check(!Market.of(ref).shops().isEmpty(),"shop career survives restart");check(Market.of(ref).sales()>0,"shop sales survive restart");
   source.sendSuccess(()->Component.literal("VP090 persistence PASS"),false);return 1;
  }
  var dayRule=level.getGameRules();boolean advanceTime=dayRule.get(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME);
  dayRule.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME,false,level.getServer());
  try {
  passed=0;
  // Remote synthetic plateau. Existing CTOV villages and their buildings are outside this area.
  for(int x=-104;x<=104;x++)for(int z=-104;z<=104;z++) {
   BlockPos floor=A.offset(x,-1,z);level.getChunk(floor);level.setBlock(floor,Blocks.GRASS_BLOCK.defaultBlockState(),2);
   for(int y=0;y<=5;y++)level.setBlock(A.offset(x,y,z),Blocks.AIR.defaultBlockState(),2);
  }
  level.setChunkForced((A.getX()+30)>>4,(A.getZ()+30)>>4,false);
  // Loading the fixture plateau can load villagers persisted by an interrupted prior run. Sweep only after those chunks load.
  String fixtureCaravan="vp_caravan_bell:"+A.asLong();
  for(Entity e:level.getEntitiesOfClass(Entity.class,new AABB(A).inflate(130),x->x.entityTags().contains("vp090_fixture")
   ||x.entityTags().contains(fixtureCaravan)||x.entityTags().contains("vp_caravan_cargo_visual")||x.entityTags().contains("vp_caravan_porter_visual")))e.discard();
  VillageRef a=fixture(level,A),b=fixture(level,B);a.bellEntity().removeAttached(VillagerPlus.CONSTRUCTION);Container ca=a.chest(level), cb=b.chest(level);
  passed+=DevelopmentChecks.run(level,a);
  check(Villages.claimMorningBell(a,7L)&&!Villages.claimMorningBell(a,7L)
   &&Villages.claimMorningBell(a,8L),"morning bell rings once per village and day");
  check(a.bellEntity().getAttachedOrElse(VillagerPlus.LAST_MORNING_BELL,Long.MIN_VALUE)==8L,
   "morning bell day marker is stored on its persistent village attachment");
  VillageData resumedPlan=new VillageData();resumedPlan.planType=Blueprints.GRANARY;resumedPlan.planOrigin=Optional.of(A);
  check(Builder.planSize(resumedPlan,new ConstructionSite(A,List.of(),List.of(229,293,340,530,543),true,List.of()))==543,
   "saved construction phase totals show the full plan before the builder cache reloads");
  passed+=de.villagerplus.build.MilestoneWaitingChecks.run(level,a);
  passed+=de.villagerplus.job.AntiStuckChecks.run(level,a);
  passed+=MiningSafetyChecks.run(level,new BlockPos(13000,310,13000));
  passed+=de.villagerplus.job.MineLifecycleChecks.run(level,new BlockPos(24000,280,24000));
  passed+=EquipmentProductionChecks.run(level,new BlockPos(14000,280,14000));
  passed+=de.villagerplus.job.SmithRailCraftChecks.run(level,new BlockPos(14500,280,14500));
  passed+=de.villagerplus.build.WallConstructionChecks.run(level,a);
  passed+=de.villagerplus.build.BuilderStandChecks.run(level,a);
  passed+=StaffingChecks.run(level,a);
  passed+=de.villagerplus.job.CharcoalChecks.run(level,a);
  passed+=de.villagerplus.job.SmithSmeltingChecks.run(level,a);
  passed+=de.villagerplus.job.DelightKitchenCraftingChecks.run(level,A.offset(500,0,500));
  passed+=de.villagerplus.job.HouseholdMealChecks.run(level,A.offset(560,0,560));
  passed+=de.villagerplus.job.AnimChecks.run(level,a);
  passed+=de.villagerplus.job.WoodcuttingCycleChecks.run();
  passed+=de.villagerplus.job.EquipmentMaintenanceChecks.run(level,A.offset(400,0,0));
  passed+=ZonePolicyChecks.run(a);
  passed+=de.villagerplus.village.RailTransportChecks.run();
  passed+=de.villagerplus.village.RoadsChecks.run();
  passed+=de.villagerplus.job.RoadMaintenanceChecks.run(level,new BlockPos(15000,201,15000));
  passed+=de.villagerplus.job.RoadConstructionChecks.run(level,new BlockPos(16000,280,16000));
  passed+=de.villagerplus.job.RoadBridgeChecks.run(level,new BlockPos(17000,280,17000));
  passed+=de.villagerplus.village.CaravanSiteChecks.run(level,new BlockPos(18000,280,18000));
  passed+=de.villagerplus.village.CaravanCampChecks.run();
  passed+=de.villagerplus.village.CaravanCampChecks.runWorld(level,new BlockPos(18000,280,18000));
  passed+=de.villagerplus.village.RoadRouteChecks.run(level,new BlockPos(23000,280,23000));
  BlockPos caravanRoadFixture=new BlockPos(22000,280,22000);
  for(int x=-16;x<=48;x+=16)for(int z=-16;z<=48;z+=16)level.getChunkAt(caravanRoadFixture.offset(x,0,z));
  passed+=de.villagerplus.village.CaravanRoadChecks.run(level,caravanRoadFixture);
  passed+=de.villagerplus.job.RoadClearingChecks.run(level,new BlockPos(15000,201,15000));
  passed+=de.villagerplus.village.RoadTerrainChecks.run(level,A.offset(420,0,420));
  passed+=de.villagerplus.build.BuilderPathChecks.run(level,A.offset(460,0,460));
  passed+=de.villagerplus.village.CivicCenterChecks.run(level,a);
  passed+=BuildingRelocationChecks.run(level,a);
  passed+=de.villagerplus.village.BuildingRelocationWorldChecks.run(level,a);
  passed+=de.villagerplus.job.FarmingChecks.run(level,a);
  passed+=de.villagerplus.job.FarmZoneChecks.run(level,a);
  passed+=de.villagerplus.job.FarmerExpansionChecks.run(level,a);
  passed+=de.villagerplus.job.LivestockZoneChecks.run(level,a);
  constructionChecks(level,a,ca);
  passed+=BuilderChecks.run();
  recipeCraftingChecks(level,a,ca);
  passed+=de.villagerplus.job.RecipeCraftingChecks.run(level);
  passed+=de.villagerplus.build.ConstructionZoneChecks.run(level,a);
  apprenticeChecks(level,a);
  mealChecks(level,a);
  railLedgerChecks(level,a,b,ca);
  Villager stockTester=EntityTypes.VILLAGER.spawn(level,A.east(6),EntitySpawnReason.COMMAND);stockTester.addTag("vp090_fixture");stockTester.setNoAi(true);
  try {passed+=StockPolicyChecks.run(level,a,stockTester);} finally {stockTester.discard();}
  passed+=RoleChecks.run(level,A.offset(60,0,60),a);
  farmingLivestockForestryChecks(level);
  villagerInfoChecks(level,player,a);
  Network.of(a.bellEntity());Network.of(b.bellEntity());
  check(Network.of(a.bellEntity()).partners().size()==3,"three deterministic partner towns");
  var relationBase=TradeNetwork.create(A).withLinks(List.of(new TradeNetwork.Link("minecraft:overworld",B,"Nachbar",10,false,5,0)));
  var relationAfter=(TradeNetwork)call(Caravan.class,"updatedRelation",new Class[]{TradeNetwork.class,int.class,int.class,long.class},relationBase,0,3,level.getGameTime()/24000);
  check(relationAfter.links().get(0).relation()==16&&relationAfter.partners().get(0).relation()==relationBase.partners().get(0).relation()+3,"caravan relation persists both neighbor and partner updates");
  check((Boolean)call(School.class,"isSchoolActivity",new Class[]{String.class},"lernt beim Lehrer Ada")&&(Boolean)call(School.class,"isSchoolActivity",new Class[]{String.class},"lernt in der Schule (Lehrer fehlt)"),"school activity keeps teacher and independent lessons active");
  ItemStack link=card(level,player,a);redeem(level,player,B,link);
  check(Network.of(a.bellEntity()).links().size()==1&&Network.of(b.bellEntity()).links().size()==1,"reciprocal map link");
  passed+=de.villagerplus.village.NetworkNeighborChecks.inviteAction(level,player,b,a);
  ItemStack dup=card(level,player,a);int old=dup.getCount();redeem(level,player,B,dup);check(dup.getCount()==old&&Network.of(a.bellEntity()).links().size()==1,"duplicate map preserved");
  ItemStack self=card(level,player,a);old=self.getCount();redeem(level,player,A,self);check(self.getCount()==old,"self-link rejected");
  ItemStack cross=card(level,player,a);var tag=cross.get(DataComponents.CUSTOM_DATA).copyTag();tag.putString("dim","minecraft:the_nether");cross.set(DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(tag));old=cross.getCount();redeem(level,player,B,cross);check(cross.getCount()==old,"cross-dimension map rejected");
  long now=level.getGameTime();Network.act(level,player,A,"greet",0);
  check(Network.of(a.bellEntity()).links().get(0).relation()==15&&Network.of(b.bellEntity()).links().get(0).relation()==15,"greeting reciprocal relation");
  Network.act(level,player,B,"greet",0);check(Network.of(a.bellEntity()).links().get(0).relation()==15,"reverse direction cannot bypass cooldown");
  check(Network.moodBonus(level,a)==3&&Network.moodBonus(level,b)==3,"greeting mood available");
  var beforeCaravanRelation=Network.of(a.bellEntity());int oldLinkRelation=beforeCaravanRelation.links().get(0).relation(),oldPartnerRelation=beforeCaravanRelation.partners().get(0).relation();
  call(Caravan.class,"changeRelation",new Class[]{VillageRef.class,int.class,int.class,long.class},a,0,3,-1L);
  check(Network.of(a.bellEntity()).links().get(0).relation()==oldLinkRelation+6&&Network.of(a.bellEntity()).partners().get(0).relation()==oldPartnerRelation+3,"caravan relation persists reciprocal link and partner updates");
  Network.act(level,player,A,"route",0);check(Network.of(a.bellEntity()).links().get(0).route()&&Network.of(b.bellEntity()).links().get(0).route(),"route reciprocal");
  lot(ca,Items.IRON_INGOT,128);lot(cb,Items.BREAD,128);Network.act(level,player,A,"swap",0);
  check(total(ca,Items.IRON_INGOT)+total(cb,Items.IRON_INGOT)==128&&total(ca,Items.BREAD)+total(cb,Items.BREAD)==128,"swap conserves all goods");
  check(total(ca,Items.BREAD)>0&&total(cb,Items.IRON_INGOT)>0,"swap actually moves goods");
  check((128-total(ca,Items.IRON_INGOT))*24==total(ca,Items.BREAD)*6,"swap equal value");
  int before=total(ca,Items.BREAD);Network.act(level,player,B,"swap",0);check(total(ca,Items.BREAD)==before,"swap reciprocal cooldown");
  Network.save(a.bellEntity(),Network.of(a.bellEntity()).withCooldown(Network.pairKey(a,b,"swap"),0));Network.save(b.bellEntity(),Network.of(b.bellEntity()).withCooldown(Network.pairKey(a,b,"swap"),0));
  ca.clearContent();cb.clearContent();for(int i=0;i<ca.getContainerSize();i++)ca.setItem(i,new ItemStack(Items.IRON_INGOT,64));for(int i=0;i<cb.getContainerSize();i++)cb.setItem(i,new ItemStack(Items.BREAD,64));
  int iron=total(ca,Items.IRON_INGOT),bread=total(cb,Items.BREAD);Network.act(level,player,A,"swap",0);check(total(ca,Items.IRON_INGOT)==iron&&total(cb,Items.BREAD)==bread,"full inventory rejection is atomic");
  ca.clearContent();cb.clearContent();var named=new ItemStack(Items.IRON_INGOT,64);named.set(DataComponents.CUSTOM_NAME,Component.literal("Named test iron"));ca.setItem(0,named);lot(cb,Items.BREAD,128);
  Network.act(level,player,A,"swap",0);check(total(ca,Items.IRON_INGOT)==64&&total(cb,Items.IRON_INGOT)==0,"renamed goods cannot duplicate");
  var networkData=Network.of(a.bellEntity());var json=TradeNetwork.CODEC.encodeStart(JsonOps.INSTANCE,networkData).getOrThrow();check(TradeNetwork.CODEC.parse(JsonOps.INSTANCE,json).getOrThrow().equals(networkData),"network codec round trip");
  ca.clearContent();ca.setItem(0,new ItemStack(Items.EMERALD,20));ca.setItem(1,new ItemStack(Items.BREAD,64));
  Villager seller=EntityTypes.VILLAGER.spawn(level,A.east(18),EntitySpawnReason.COMMAND);seller.addTag("vp090_fixture");seller.setInvulnerable(true);
  var sm=seller.getAttachedOrCreate(VillagerPlus.MEMORY);sm.name="Marta Markt";
  int beforeEmerald=total(ca,Items.EMERALD);call(Plaza.class,"build",new Class[]{ServerLevel.class,VillageRef.class,BlockPos.class,Villager.class},level,a,A.east(24),seller);
  check(total(a.chest(level),Items.EMERALD)==beforeEmerald&&total(ca,Items.EMERALD)==0,"plaza migration preserves inventory");
  check(Plaza.inside(level,a,A.east(24),0)&&!Plaza.inside(level,a,A.east(24).offset(7,0,7),0),"plaza octagonal footprint");
  check(!Sites.okRect(level,A.east(24),-2,2,-2,2,4),"houses cannot occupy plaza");
  var p=Palette.detect(level,A,seller,1);var shop=new MarketData.Shop(seller.getUUID(),A.east(37),Direction.WEST.get2DDataValue(),0,0,0,now,p.ids(),false,0);
  var market=Market.of(a);Market.save(a,new MarketData(market.plaza(),List.of(shop),now+100000,0));
  var supplies=a.chest(level);supplies.clearContent();Map<Item,Integer> needed=new HashMap<>();for(Step st:Market.hut(shop,p))if(!st.free())needed.merge(st.item(),st.cost(),Integer::sum);
  int slot=0;for(var entry:needed.entrySet()){int n=entry.getValue();while(n>0){int k=Math.min(64,n);supplies.setItem(slot++,new ItemStack(entry.getKey(),k));n-=k;}}
  for(int i=0;i<Market.hut(shop,p).size()+10;i++) {
   var current=Market.shop(a,seller.getUUID());if(current.opened()>0)break;
   Step st=Market.hut(current,p).get(current.progress());seller.teleportTo(current.origin().getX()-2.5,current.origin().getY(),current.origin().getZ()+.5);
   check(Market.work(new Ctx(level,seller,JobManager.state(seller),sm,a,now+i*30)),"shop construction step "+i);
  }
  var opened=Market.shop(a,seller.getUUID());check(opened.opened()>0,"shop built with real resources");
  BlockPos stock=(BlockPos)call(Market.class,"stockPos",new Class[]{MarketData.Shop.class},opened);var stockBox=HopperBlockEntity.getContainerAt(level,stock);
  check(stockBox!=null&&level.getBlockEntity(stock).getAttached(VillagerPlus.OWNER).equals(seller.getUUID()),"shop stock private ownership");
  // A real wholesale purchase and customer sale, with all money accounted for.
  lot(a.chest(level),Items.BREAD,64);seller.getInventory().clearContent();seller.getInventory().addItem(new ItemStack(Items.EMERALD,2));
  seller.teleportTo(opened.origin().getX()+.5,opened.origin().getY(),opened.origin().getZ()+.5);
  call(Market.class,"restock",new Class[]{Ctx.class,MarketData.Shop.class},new Ctx(level,seller,JobManager.state(seller),sm,a,now+5000),opened);
  check(total(stockBox,Items.BREAD)==8&&total(a.chest(level),Items.BREAD)==56&&total(a.chest(level),Items.EMERALD)==1&&total(seller.getInventory(),Items.EMERALD)==1,"wholesale conserves goods and emeralds");
  Villager customer=EntityTypes.VILLAGER.spawn(level,opened.origin().west(3),EntitySpawnReason.COMMAND);customer.addTag("vp090_fixture");customer.setInvulnerable(true);customer.getInventory().addItem(new ItemStack(Items.EMERALD,2));
  var cm=customer.getAttachedOrCreate(VillagerPlus.MEMORY);cm.lastMeal=level.getGameTime()-Config.HUNGER_AFTER;
  check(Market.open(level),"shop purchase runs inside the configured market hours");
  boolean bought=Market.visit(new Ctx(level,customer,JobManager.state(customer),cm,a,now+10000));
  check(bought&&Market.of(a).sales()==1,"villager customer buys at shop");
  check(total(stockBox,Items.BREAD)==4&&total(customer.getInventory(),Items.BREAD)==4&&total(customer.getInventory(),Items.EMERALD)==1&&total(seller.getInventory(),Items.EMERALD)==2,"retail conserves goods and emeralds");
  check(MarketData.CODEC.parse(JsonOps.INSTANCE,MarketData.CODEC.encodeStart(JsonOps.INSTANCE,Market.of(a)).getOrThrow()).getOrThrow().equals(Market.of(a)),"market codec round trip");
  check(!Market.visit(new Ctx(level,customer,JobManager.state(customer),cm,a,now+10001)),"shopping cooldown persistent");
  // High-terracotta designs: every wall/gable remains at most half terracotta, across seeds.
  Palette terra=new Palette(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse("minecraft:white_terracotta")),net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse("minecraft:pink_terracotta")),null,Blocks.OAK_PLANKS,Blocks.SPRUCE_PLANKS,Blocks.SPRUCE_STAIRS,Blocks.SPRUCE_SLAB,Blocks.GLASS_PANE,Blocks.OAK_PLANKS,Blocks.OAK_DOOR,Blocks.OAK_FENCE);
  for(int seed=0;seed<64;seed++) {
   var design=Architect.design(Architect.Kind.HOUSE,seed);var steps=Architect.build(design,terra,A,Direction.NORTH);
   long t=steps.stream().filter(st->st.item()==net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse("minecraft:white_terracotta"))||st.item()==net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse("minecraft:pink_terracotta"))).count();
   long solid=steps.stream().filter(st->!st.free()&&st.pos().getY()>=A.getY()&&(st.item()==net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse("minecraft:white_terracotta"))||st.item()==net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse("minecraft:pink_terracotta"))||st.item()==Items.STONE_BRICKS)).count();
   check(t*2<=solid,"terracotta bound seed "+seed);
  }
  // Force a visible caravan on the remote plateau; it is left running for the navigation check.
  a.data().extra.nextCaravan=0;check(Caravan.spawn(level,a),"caravan spawned");
  var traders=level.getEntitiesOfClass(de.villagerplus.entity.CaravanTraderEntity.class,new AABB(A).inflate(130),v->v.entityTags().contains("vp_caravan"));
  check(traders.size()==1&&traders.get(0).getType()==de.villagerplus.entity.ModEntities.CARAVAN_TRADER
   &&traders.get(0).getClass()==de.villagerplus.entity.CaravanTraderEntity.class,"one independent caravan trader entity with its isolated renderer id");var trader=traders.get(0);
  double distance=Math.hypot(trader.getX()-A.getX(),trader.getZ()-A.getZ());check(distance>=69&&distance<=101,"caravan starts 70-100 blocks away");
  check(trader.getOffers().isEmpty()&&trader.hasCustomName()&&trader.tradeSpecialty()>=0&&trader.visitData()!=null,
   "new trader does not publish offers before physical caravan stock is loaded");
  check(level.getEntitiesOfClass(Entity.class,new AABB(trader.blockPosition()).inflate(8),e->e.getType()==EntityTypes.DONKEY).size()==2,"two chested donkeys");
  var activeField=Caravan.class.getDeclaredField("ACTIVE");activeField.setAccessible(true);var active=(Map<UUID,Object>)activeField.get(null);Object caravanState=active.get(trader.getUUID());
  trader.setTradeProfile(0,0,trader.partnerName());
  ItemStack backedIron=new ItemStack(Items.IRON_INGOT,16);
  int backedPacked=(Integer)call(Caravan.class,"packCargo",new Class[]{ServerLevel.class,caravanState.getClass(),ItemStack.class},level,caravanState,backedIron);
   ItemStack shortCoal=new ItemStack(Items.COAL,7);
   int shortCoalPacked=(Integer)call(Caravan.class,"packCargo",new Class[]{ServerLevel.class,caravanState.getClass(),ItemStack.class},level,caravanState,shortCoal);
   int shortCoalDebit=(Integer)call(Caravan.class,"takeMerchantCargo",new Class[]{ServerLevel.class,caravanState.getClass(),Item.class,int.class},level,caravanState,Items.COAL,8);
   int shortCoalLeft=(Integer)call(Caravan.class,"availableMerchantCargo",new Class[]{ServerLevel.class,caravanState.getClass(),Item.class},level,caravanState,Items.COAL);
   check(shortCoalPacked==7&&shortCoalDebit==0&&shortCoalLeft==7,
    "an incomplete cargo reservation is rejected without debiting its available coal");
  call(Caravan.class,"prepareBackedMerchantOffers",new Class[]{ServerLevel.class,caravanState.getClass(),de.villagerplus.entity.CaravanTraderEntity.class},level,caravanState,trader);
  var ironOffer=trader.getOffers().stream().filter(offer->offer.getResult().is(Items.IRON_INGOT)).findFirst().orElse(null);
  int backedIronCount=trader.backedTradeStockSnapshot().stream().filter(stack->stack.is(Items.IRON_INGOT)).mapToInt(ItemStack::getCount).sum();
  check(backedPacked==16&&ironOffer!=null&&ironOffer.getResult().getCount()==8&&ironOffer.getMaxUses()==2
   &&backedIronCount==16&&trader.backedOffersCovered(),"merchant offer budget is backed by exact reserved physical cargo");
  ironOffer.increaseUses();trader.notifyTrade(ironOffer);
  backedIronCount=trader.backedTradeStockSnapshot().stream().filter(stack->stack.is(Items.IRON_INGOT)).mapToInt(ItemStack::getCount).sum();
  int paidEmeralds=trader.backedTradeStockSnapshot().stream().filter(stack->stack.is(Items.EMERALD)).mapToInt(ItemStack::getCount).sum();
  check(backedIronCount==8&&paidEmeralds==4&&trader.backedOffersCovered(),"completed trade atomically removes its exact goods and retains the real emerald payment");
  check(!trader.consumeBackedTradeStock(new ItemStack(Items.IRON_INGOT,9))
   &&trader.backedTradeStockSnapshot().stream().filter(stack->stack.is(Items.IRON_INGOT)).mapToInt(ItemStack::getCount).sum()==8,
   "an overdrawn offer-stock debit is rejected without a partial deduction");
  a.bellEntity().removeAttached(VillagerPlus.STOCK_POLICIES);
  ItemStack testCargo=new ItemStack(Items.SPRUCE_PLANKS,3);int packed=(Integer)call(Caravan.class,"packCargo",new Class[]{ServerLevel.class,caravanState.getClass(),ItemStack.class},level,caravanState,testCargo);
  check(packed==3&&testCargo.isEmpty()&&(Boolean)call(Caravan.class,"hasCargo",new Class[]{ServerLevel.class,caravanState.getClass()},level,caravanState),"real goods pack into donkey cargo slots");
  var donkey=level.getEntitiesOfClass(net.minecraft.world.entity.animal.equine.Donkey.class,new AABB(trader.blockPosition()).inflate(8)).stream().filter(d->{for(int cargoSlot=2;cargoSlot<17;cargoSlot++){var access=d.getSlot(500+cargoSlot);if(access!=null&&access.get().is(Items.SPRUCE_PLANKS))return true;}return false;}).findFirst().orElseThrow();
  var visualsField=Caravan.class.getDeclaredField("CARGO_VISUALS");visualsField.setAccessible(true);var visuals=(Map<UUID,UUID>)visualsField.get(null);
  var displayCandidates=level.getEntitiesOfClass(net.minecraft.world.entity.Display.ItemDisplay.class,new AABB(donkey.blockPosition()).inflate(6));
  var cargoVisual=displayCandidates.stream().filter(x->x.getUUID().equals(visuals.get(donkey.getUUID()))).findFirst().orElse(null);
  String visualInfo="map="+visuals+", donkey="+donkey.getUUID()+", nearby="+displayCandidates.stream().map(x->x.getUUID()+":"+x.getItemStack()).toList()+", display="+cargoVisual+(cargoVisual==null?"":" item="+cargoVisual.getItemStack()+" vehicle="+cargoVisual.getVehicle());
  check(cargoVisual!=null&&cargoVisual.getItemStack().is(Items.SPRUCE_PLANKS)&&cargoVisual.getVehicle()==donkey,"donkey cargo has a visible ride-along item display: "+visualInfo);
  cargoVisual.stopRiding();call(Caravan.class,"syncCargoVisual",new Class[]{ServerLevel.class,net.minecraft.world.entity.animal.equine.Donkey.class},level,donkey);
  check(cargoVisual.getVehicle()==donkey,"loaded cargo marker reattaches to its donkey");
  var duplicate=EntityTypes.ITEM_DISPLAY.create(level,EntitySpawnReason.COMMAND);duplicate.addTag("vp_caravan_cargo_visual");duplicate.addTag("vp_caravan_cargo_owner:"+donkey.getUUID());
  duplicate.setItemStack(new ItemStack(Items.SPRUCE_PLANKS));duplicate.setPos(donkey.getX(),donkey.getY()+1.2,donkey.getZ());check(level.addFreshEntity(duplicate),"duplicate cargo marker fixture loads");
  call(Caravan.class,"syncCargoVisual",new Class[]{ServerLevel.class,net.minecraft.world.entity.animal.equine.Donkey.class},level,donkey);
  check(duplicate.isRemoved()&&level.getEntitiesOfClass(net.minecraft.world.entity.Display.ItemDisplay.class,donkey.getBoundingBox().inflate(4),e->e.entityTags().contains("vp_caravan_cargo_owner:"+donkey.getUUID())).size()==1,
   "cargo marker sync is idempotent when a saved display loads after its donkey");
  for(int i=0;i<ca.getContainerSize();i++)ca.setItem(i,new ItemStack(Items.STONE,64));
  for(Container depot:Balance.storage(level,a))for(int i=0;i<depot.getContainerSize();i++)depot.setItem(i,new ItemStack(Items.STONE,64));
  boolean blockedUnload=!(Boolean)call(Caravan.class,"unloadOne",new Class[]{ServerLevel.class,VillageRef.class,caravanState.getClass()},level,a,caravanState);
  check(blockedUnload,"caravan keeps cargo when every communal depot is full");
  check((Boolean)call(Caravan.class,"hasCargo",new Class[]{ServerLevel.class,caravanState.getClass()},level,caravanState),"full communal storage preserves the donkey's real cargo");
  check(!cargoVisual.isRemoved()&&cargoVisual.getItemStack().is(Items.SPRUCE_PLANKS),"blocked transfer preserves the visible cargo marker");
  for(Container depot:Balance.storage(level,a))depot.clearContent();
  donkey.teleportTo(A.getX()+0.5,A.getY(),A.getZ()+8.5);
  Villager routeTester=EntityTypes.VILLAGER.spawn(level,A.east(45),EntitySpawnReason.COMMAND);routeTester.addTag("vp090_fixture");routeTester.setNoAi(true);
  BlockPos reachablePickup=(BlockPos)call(Caravan.class,"reachablePorterStand",new Class[]{Ctx.class,BlockPos.class},
   new Ctx(level,routeTester,JobManager.state(routeTester),routeTester.getAttachedOrCreate(VillagerPlus.MEMORY),a,now+29999),donkey.blockPosition());
  check(reachablePickup!=null&&Math.abs(reachablePickup.getX()-donkey.getBlockX())+Math.abs(reachablePickup.getZ()-donkey.getBlockZ())==1,
   "distant caravan pickup chooses a reachable clear stand beside the donkey");routeTester.discard();
  Villager porter=EntityTypes.VILLAGER.spawn(level,A.east(7),EntitySpawnReason.COMMAND);porter.addTag("vp090_fixture");porter.setNoAi(true);
  porter.teleportTo(donkey.getX()+1.0,donkey.getY(),donkey.getZ()+0.5);
  var porterMemory=porter.getAttachedOrCreate(VillagerPlus.MEMORY);var porterState=JobManager.state(porter);var porterJob=new PorterJob();
  check(porterJob.primary(new Ctx(level,porter,porterState,porterMemory,a,now+30000)),"porter claims and visibly lifts a donkey cargo stack");
  var carried=level.getEntitiesOfClass(net.minecraft.world.entity.Display.ItemDisplay.class,new AABB(porter.blockPosition()).inflate(4),d->d.entityTags().contains("vp_caravan_porter_visual"));
  check(carried.size()==1&&carried.get(0).getVehicle()==porter&&donkey.getSlot(502).get().getCount()==3&&total(a.chest(level),Items.SPRUCE_PLANKS)==0,
   "carried visual follows porter while all real goods remain on donkey before arrival");
  Villager porterTwo=EntityTypes.VILLAGER.spawn(level,A.east(10),EntitySpawnReason.COMMAND);porterTwo.addTag("vp090_fixture");porterTwo.setNoAi(true);
  boolean duplicateClaim=Caravan.porterDelivery(new Ctx(level,porterTwo,JobManager.state(porterTwo),porterTwo.getAttachedOrCreate(VillagerPlus.MEMORY),a,now+30001));
  check(!duplicateClaim,"a second porter cannot claim the same donkey cargo stack");porterTwo.discard();
  Caravan.releasePorter(porter);
  check(donkey.getSlot(502).get().getCount()==3&&carried.get(0).isRemoved()&&cargoVisual.getVehicle()==donkey&&cargoVisual.getItemStack().is(Items.SPRUCE_PLANKS),
   "cancelled visible carry restores the donkey marker without changing cargo");
  porter.teleportTo(donkey.getX()+1.0,donkey.getY(),donkey.getZ()+0.5);porterState.clearTarget();porterState.cooldownUntil=0;
  check(porterJob.primary(new Ctx(level,porter,porterState,porterMemory,a,now+30020)),"porter can resume a released cargo stack");
  BlockPos porterStand=(BlockPos)call(Caravan.class,"porterStand",new Class[]{ServerLevel.class,BlockPos.class},level,a.chestPos(level));
  check(porterStand!=null,"village crate has a safe adjacent delivery stand");porter.teleportTo(porterStand.getX()+0.5,porterStand.getY(),porterStand.getZ()+0.5);
  boolean delivered=porterJob.primary(new Ctx(level,porter,porterState,porterMemory,a,now+30040));
  check(delivered&&total(a.chest(level),Items.SPRUCE_PLANKS)==3,"reached porter commits cargo into the material crate");
  check(!(Boolean)call(Caravan.class,"hasCargo",new Class[]{ServerLevel.class,caravanState.getClass()},level,caravanState)
   &&!visuals.containsKey(donkey.getUUID())&&cargoVisual.isRemoved(),"successful unload clears the cargo slot and its visual");
  // A real merchant trade sells only above the reserve, pays into donkey cargo, and spends pre-existing village emeralds.
  Container tradeBox=a.chest(level);for(Container depot:Balance.storage(level,a))depot.clearContent();
  lot(tradeBox,Items.OAK_LOG,192);tradeBox.setItem(3,new ItemStack(Items.EMERALD,3));a.data().wanted.clear();a.data().wanted.add("minecraft:iron_ingot#64");
  check(total(tradeBox,Items.OAK_LOG)==192&&total(tradeBox,Items.EMERALD)==3,"merchant trade fixture uses the village's current registered depot");
  call(Caravan.class,"exchange",new Class[]{ServerLevel.class,VillageRef.class,net.minecraft.world.entity.npc.villager.AbstractVillager.class},level,a,trader);
  var cargoDonkeysField=caravanState.getClass().getDeclaredField("llamas");cargoDonkeysField.setAccessible(true);
  int cargoEmeralds=0,cargoIron=0,paymentSlot=-1;net.minecraft.world.entity.animal.equine.Donkey paymentDonkey=null;for(UUID donkeyId:(List<UUID>)cargoDonkeysField.get(caravanState))
   if(level.getEntity(donkeyId) instanceof net.minecraft.world.entity.animal.equine.Donkey tradeDonkey)for(int tradeSlot=2;tradeSlot<17;tradeSlot++){
    var access=tradeDonkey.getSlot(500+tradeSlot);if(access!=null){if(access.get().is(Items.EMERALD)){cargoEmeralds+=access.get().getCount();paymentDonkey=tradeDonkey;paymentSlot=tradeSlot;}if(access.get().is(Items.IRON_INGOT))cargoIron+=access.get().getCount();}}
  check(total(tradeBox,Items.OAK_LOG)==64&&total(tradeBox,Items.EMERALD)==0,"caravan sale preserves village log reserve and charges real emerald balance; logs="+total(tradeBox,Items.OAK_LOG)+", emeralds="+total(tradeBox,Items.EMERALD));
  check(cargoEmeralds==6&&cargoIron==41,"surplus earns capped emerald payment while trade credit and three real emeralds buy iron; emeralds="+cargoEmeralds+", iron="+cargoIron);
  porter.teleportTo(donkey.getX()+1.0,donkey.getY(),donkey.getZ()+0.5);porterState.clearTarget();porterState.cooldownUntil=0;
  check(porterJob.primary(new Ctx(level,porter,porterState,porterMemory,a,now+30060)),"porter claims the requested iron before merchant payment");
  var paymentVisual=level.getEntitiesOfClass(net.minecraft.world.entity.Display.ItemDisplay.class,new AABB(porter.blockPosition()).inflate(4),d->d.entityTags().contains("vp_caravan_porter_visual"));
  check(paymentVisual.size()==1&&paymentVisual.get(0).getVehicle()==porter&&paymentVisual.get(0).getItemStack().is(Items.IRON_INGOT)
   &&total(tradeBox,Items.EMERALD)==0,"porter visibly carries requested iron before merchant payment");
  porter.teleportTo(porterStand.getX()+0.5,porterStand.getY(),porterStand.getZ()+0.5);
  check(porterJob.primary(new Ctx(level,porter,porterState,porterMemory,a,now+30080))&&total(tradeBox,Items.IRON_INGOT)==41,
   "porter commits requested iron to the material depot before carrying cash");
  porter.teleportTo(paymentDonkey.getX()+1.0,paymentDonkey.getY(),paymentDonkey.getZ()+0.5);porterState.clearTarget();porterState.cooldownUntil=0;
  check(porterJob.primary(new Ctx(level,porter,porterState,porterMemory,a,now+30090)),"porter claims the merchant's emerald payment after requested goods");
  paymentVisual=level.getEntitiesOfClass(net.minecraft.world.entity.Display.ItemDisplay.class,new AABB(porter.blockPosition()).inflate(4),d->d.entityTags().contains("vp_caravan_porter_visual"));
  check(paymentVisual.size()==1&&paymentVisual.get(0).getVehicle()==porter&&paymentVisual.get(0).getItemStack().is(Items.EMERALD)
   &&total(tradeBox,Items.EMERALD)==0,"porter visibly carries merchant emeralds to the village treasury");
  BlockPos treasuryStand=(BlockPos)call(Caravan.class,"porterStand",new Class[]{ServerLevel.class,BlockPos.class},level,a.bell());
  check(treasuryStand!=null,"bell has a safe approach position for village cash delivery");
  porter.teleportTo(treasuryStand.getX()+0.5,treasuryStand.getY(),treasuryStand.getZ()+0.5);
  check(porterJob.primary(new Ctx(level,porter,porterState,porterMemory,a,now+30100))&&Network.treasuryEmeralds(a)==6
   &&paymentDonkey.getSlot(500+paymentSlot).get().isEmpty(),"porter commits physical emerald cargo to the persistent village treasury ledger");
  var treasuryNetwork=Network.of(a.bellEntity());
  check(TradeNetwork.CODEC.parse(JsonOps.INSTANCE,TradeNetwork.CODEC.encodeStart(JsonOps.INSTANCE,treasuryNetwork).getOrThrow()).getOrThrow().equals(treasuryNetwork),"village treasury balance survives network codec round trip");
  tradeBox.clearContent();tradeBox.setItem(0,new ItemStack(Items.WHEAT_SEEDS,32));tradeBox.setItem(1,new ItemStack(Items.TORCH,16));
  tradeBox.setItem(2,new ItemStack(Items.OAK_SAPLING,4));a.data().wanted.clear();a.data().wanted.add("minecraft:gravel#32");
  Optional<BlockPos> savedPen=a.data().extra.pen;a.data().extra.pen=Optional.empty();
  call(Caravan.class,"exchange",new Class[]{ServerLevel.class,VillageRef.class,net.minecraft.world.entity.npc.villager.AbstractVillager.class},level,a,trader);
  int remainingTreasury=(int)Network.treasuryEmeralds(a);
  a.data().extra.pen=savedPen;
  boolean gravelCargo=false;for(UUID donkeyId:(List<UUID>)cargoDonkeysField.get(caravanState))
   if(level.getEntity(donkeyId) instanceof net.minecraft.world.entity.animal.equine.Donkey tradeDonkey)for(int gravelSlot=2;gravelSlot<17;gravelSlot++){
    var access=tradeDonkey.getSlot(500+gravelSlot);if(access!=null&&access.get().is(Items.GRAVEL)&&access.get().getCount()>=32)gravelCargo=true;}
  check(remainingTreasury==5&&gravelCargo,"next caravan spends exactly one treasury emerald and carries the purchased gravel; balance="+remainingTreasury);
  a.data().wanted.clear();
  // A remote communal deposit must retain one walk timer until it reaches the destination.
  porter.getInventory().clearContent();porter.getInventory().addItem(new ItemStack(Items.OAK_LOG,32));
  porter.teleportTo(A.getX()+50.5,A.getY(),A.getZ()+0.5);porterState.clearTarget();porterState.cooldownUntil=0;
  long depositStart=now+30110;
  check(JobUtil.depositSurplus(new Ctx(level,porter,porterState,porterMemory,a,depositStart),s->s.is(Items.OAK_LOG),16,0)
   &&a.chestPos(level).equals(porterState.target)&&porterState.walkStart==depositStart,
   "long communal deposit starts with a stable tracked chest destination");
  check(JobUtil.depositSurplus(new Ctx(level,porter,porterState,porterMemory,a,depositStart+20),s->s.is(Items.OAK_LOG),16,0)
   &&porterState.walkStart==depositStart,"repeated deposit ticks preserve the original path timer");
  JobUtil.depositSurplus(new Ctx(level,porter,porterState,porterMemory,a,depositStart+250),s->s.is(Items.OAK_LOG),16,0);
  check(porterState.stuckCount==1,"remote deposit increments stuck retries instead of resetting them");
  JobUtil.depositSurplus(new Ctx(level,porter,porterState,porterMemory,a,depositStart+500),s->s.is(Items.OAK_LOG),16,0);
  JobUtil.depositSurplus(new Ctx(level,porter,porterState,porterMemory,a,depositStart+750),s->s.is(Items.OAK_LOG),16,0);
  check(porterState.stuckCount==0&&porterState.cooldownUntil==depositStart+1950,
   "remote deposit cancels and cools down after the third genuine stuck retry");
  porter.discard();
  var orphan=EntityTypes.DONKEY.create(level,EntitySpawnReason.COMMAND);orphan.setPos(A.getX()+30.5,A.getY(),A.getZ()+30.5);orphan.addTag("vp_caravan");orphan.addTag("vp090_fixture");
  check(level.setChunkForced((A.getX()+30)>>4,(A.getZ()+30)>>4,true),"cargo persistence fixture keeps its entity chunk loaded until restart verification");
  orphan.getSlot(499).set(new ItemStack(Items.CHEST));orphan.getSlot(502).set(new ItemStack(Items.IRON_INGOT,7));
  check(level.addFreshEntity(orphan),"cargo donkey reload fixture is admitted to the test world");
  check(!orphan.isRemoved()&&orphan.entityTags().contains("vp_caravan")&&orphan.isPersistenceRequired()
   &&orphan.getCustomName()!=null&&orphan.getCustomName().getString().equals("Karawanen-Frachtesel")
   &&orphan.getSlot(502).get().getCount()==7,"orphaned caravan donkey keeps real cargo and becomes persistent after entity load");
  var recoveredDisplayId=visuals.get(orphan.getUUID());Entity recoveredDisplay=recoveredDisplayId==null?null:level.getEntity(recoveredDisplayId);
  check(recoveredDisplay instanceof net.minecraft.world.entity.Display.ItemDisplay&&recoveredDisplay.getVehicle()==orphan,
   "recovered cargo donkey restores its ride-along display");
  var emptyOrphan=EntityTypes.DONKEY.create(level,EntitySpawnReason.COMMAND);emptyOrphan.setPos(A.getX()+32.5,A.getY(),A.getZ()+30.5);emptyOrphan.addTag("vp_caravan");emptyOrphan.addTag("vp090_fixture");
  check(level.addFreshEntity(emptyOrphan)&&!emptyOrphan.isRemoved()&&emptyOrphan.isPersistenceRequired(),
   "empty tagged member waits persistently for its trader snapshot instead of being discarded");

   // A marker-only legacy trader has real caravan members but no visit snapshot. Adopt it
   // without replaying the village exchange, and keep old offers unavailable without erasing them.
   BlockPos legacyBell=A.offset(400,0,400);level.getChunk(legacyBell);
   for(Entity stale:level.getEntitiesOfClass(Entity.class,new AABB(legacyBell).inflate(40),e->e.entityTags().contains("vp090_legacy_recovery_fixture"))) {
    active.remove(stale.getUUID());stale.discard();
   }
   VillageRef legacyVillage=fixture(level,legacyBell);
   check(legacyVillage.chestPos(level)!=null,"legacy recovery fixture has a public village destination");
   String legacyVillageTag="vp_caravan_bell:"+legacyBell.asLong();
   var legacyDonkey=EntityTypes.DONKEY.create(level,EntitySpawnReason.COMMAND);
   check(legacyDonkey!=null,"legacy caravan cargo donkey can be created");
   legacyDonkey.setPos(legacyBell.getX()+12.5,legacyBell.getY(),legacyBell.getZ()+0.5);
   legacyDonkey.addTag("vp_caravan");legacyDonkey.addTag(legacyVillageTag);legacyDonkey.addTag("vp090_legacy_recovery_fixture");
   var legacyChest=legacyDonkey.getSlot(499);var legacyCargo=legacyDonkey.getSlot(502);
   check(legacyChest!=null&&legacyCargo!=null&&legacyChest.set(new ItemStack(Items.CHEST))
    &&legacyCargo.set(new ItemStack(Items.IRON_INGOT,7)),"legacy caravan fixture stores genuine iron in its donkey chest");
   check(level.addFreshEntity(legacyDonkey),"legacy tagged cargo donkey loads before its trader");
   var legacyTrader=EntityTypes.WANDERING_TRADER.create(level,EntitySpawnReason.COMMAND);
   check(legacyTrader!=null,"legacy wandering trader can be created for migration");
   legacyTrader.setPos(legacyBell.getX()+10.5,legacyBell.getY(),legacyBell.getZ()+0.5);
   legacyTrader.addTag("vp_caravan");legacyTrader.addTag(legacyVillageTag);legacyTrader.addTag("vp090_legacy_recovery_fixture");
   int legacyOfferCount=legacyTrader.getOffers().size();
   check(level.addFreshEntity(legacyTrader),"marker-only legacy trader loads beside its tagged caravan");
   Object legacyState=active.get(legacyTrader.getUUID());
   var legacyTraded=legacyState==null?null:legacyState.getClass().getDeclaredField("traded");
   var legacyLeave=legacyState==null?null:legacyState.getClass().getDeclaredField("leaveAt");
   var legacyMembers=legacyState==null?null:legacyState.getClass().getDeclaredField("llamas");
   if(legacyState!=null){legacyTraded.setAccessible(true);legacyLeave.setAccessible(true);legacyMembers.setAccessible(true);}
   check(legacyState!=null&&legacyTraded.getBoolean(legacyState)&&legacyLeave.getLong(legacyState)==Long.MAX_VALUE
    &&legacyTrader.entityTags().contains("vp_caravan_recovery_v1"),
    "snapshot-less legacy caravan is adopted as already exchanged and gets persistent recovery markers");
   check(legacyTrader.getOffers().size()==legacyOfferCount&&!legacyTrader.getOffers().isEmpty()
    &&legacyTrader.getOffers().stream().allMatch(offer->offer.isOutOfStock()),
    "legacy merchant records are preserved but offers without a physical stock ledger stay disabled");
   check(((List<UUID>)legacyMembers.get(legacyState)).contains(legacyDonkey.getUUID())
    &&legacyDonkey.getSlot(502).get().is(Items.IRON_INGOT)&&legacyDonkey.getSlot(502).get().getCount()==7,
    "legacy trader reattaches its real cargo donkey without changing its iron inventory");
   active.remove(legacyTrader.getUUID());legacyTrader.discard();legacyDonkey.discard();
  var bd=de.villagerplus.net.BoardData.build(level,a,a.data().extra.board.orElse(A));check(bd.neighbors().size()==1&&bd.partners().size()==3,"neighbor menu payload");

  Journal.add(level,a,"Handel","Audit partner","retained trade",64);
  for(int k=0;k<180;k++)Journal.add(level,a,"Befinden","Audit resident","status "+k,0);
  check(Journal.of(a).entries().stream().anyMatch(e->e.text().equals("retained trade")),"wellbeing cannot evict trade history");
  check(Journal.of(a).entries().stream().filter(e->e.category().equals("Befinden")).count()<=96,"wellbeing history bounded");
  check(Journal.Data.CODEC.parse(JsonOps.INSTANCE,Journal.Data.CODEC.encodeStart(JsonOps.INSTANCE,Journal.of(a)).getOrThrow()).getOrThrow().equals(Journal.of(a)),"journal codec round trip");
  for(int k=0;k<6;k++){var resident=EntityTypes.VILLAGER.spawn(level,A.east(20+k),EntitySpawnReason.COMMAND);resident.addTag("vp090_fixture");resident.setNoAi(true);resident.setInvulnerable(true);}
  var original=Market.of(a);Market.save(a,new MarketData(original.plaza(),List.of(opened.died()),0,original.sales()));
  Market.plan(level,a,now+200000);var heir=Market.of(a).shops().get(0);
  check(!heir.deceased()&&!heir.owner().equals(seller.getUUID()),"shop succession selects new owner");
  check(level.getBlockEntity(stock).getAttached(VillagerPlus.OWNER).equals(heir.owner())&&total(stockBox,Items.BREAD)==4,"succession preserves private stock");
  var preserved=Market.of(a);var operator=(Villager)level.getEntity(heir.owner());
  var blocked=new MarketData.Shop(heir.owner(),A.east(55),Direction.WEST.get2DDataValue(),0,0,0,now,p.ids(),false,0);
  Market.save(a,new MarketData(original.plaza(),List.of(blocked),now+200000,original.sales()));
  Step first=Market.hut(blocked,p).get(0);level.setBlock(first.pos(),Blocks.DIAMOND_BLOCK.defaultBlockState(),3);operator.teleportTo(12052.5,201,12000.5);
  boolean busy=Market.work(new Ctx(level,operator,JobManager.state(operator),operator.getAttachedOrCreate(VillagerPlus.MEMORY),a,now+210000));
  check(!busy&&Market.shop(a,heir.owner()).retryAt()>now+210000,"blocked shop returns to normal work");
  check(level.getBlockState(first.pos()).is(Blocks.DIAMOND_BLOCK),"blocked construction preserves external blocks");
  level.setBlock(first.pos(),Blocks.GRASS_BLOCK.defaultBlockState(),3);Market.save(a,preserved);

  var bedStep=new Step(A.east(60),null,net.minecraft.tags.ItemTags.BEDS,1,false,null,block->block.defaultBlockState());
  boolean ready=(Boolean)call(Builder.class,"updateWanted",new Class[]{Ctx.class,VillageRef.class,String.class,List.class,Container.class,boolean.class},
   new Ctx(level,operator,JobManager.state(operator),operator.getAttachedOrCreate(VillagerPlus.MEMORY),a,now),a,"house",List.of(bedStep),a.chest(level),false);
  check(!ready&&a.data().wanted.stream().anyMatch(w->w.contains("bed#")),"missing tagged bed produces actionable material request");a.data().wanted.clear();
  // Keep the persisted worksite attached to a waiting plan so the normal builder tick does not classify this fixture as stale before restart.
  BlockPos persistedOrigin=A.offset(40,0,40);a.data().planType=Blueprints.HOUSE;a.data().planOrigin=Optional.of(persistedOrigin);a.data().planStep=0;
  a.data().planProgress=level.getGameTime();a.data().wanted.clear();a.data().wanted.add("minecraft:nether_star#999999");
  a.bellEntity().setAttached(VillagerPlus.CONSTRUCTION,new ConstructionSite(persistedOrigin,List.of(),List.of(3,6,9,12,15),true));a.dirty();
  source.sendSuccess(()->Component.literal("VP090 PASS "+passed+" checks. Caravan running near 12000 201 12000; inspect arrival log."),false);
  return passed;
  } finally {
   dayRule.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME,advanceTime,level.getServer());
  }
 }
}

