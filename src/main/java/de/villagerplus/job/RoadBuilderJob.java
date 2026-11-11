package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.village.Lager;
import de.villagerplus.village.Roads;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/** Physical maintenance of completed road sections; carried stock survives interruption/reload. */
public final class RoadBuilderJob implements Job {
    private enum MaterialResult { READY, WAIT, NONE }
    private static final String EXPANSION_TOOL_LOAN = "vp_expansion_tool_loan";
    private static final String EXPANSION_TOOL_BELL = "vp_expansion_tool_bell";
    private static final String EXPANSION_TOOL_SOURCE = "vp_expansion_tool_source";
    private record ExpansionTool(ItemStack stack, int slot) { }
    private record ExpansionToolLookup(ExpansionTool tool, boolean waiting) { }

    private static boolean expansionCut(Roads.Repair task) {
        return task != null && (task.action() == Roads.WorkAction.EXPANSION_CLEAR
            || task.action() == Roads.WorkAction.EXPANSION_GRADE_CUT);
    }

    private static boolean matchesExpansionTool(ItemStack stack, Roads.Repair task) {
        return stack != null && !stack.isEmpty() && task != null
            && (task.action() == Roads.WorkAction.EXPANSION_GRADE_CUT
                ? stack.is(ItemTags.SHOVELS) : task.action() == Roads.WorkAction.EXPANSION_CLEAR
                    && stack.is(ItemTags.AXES));
    }

    private static String expansionLoanSource(ItemStack stack, Ctx c) {
        if (stack == null || stack.isEmpty() || c == null || c.village() == null) return null;
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) return null;
        CompoundTag tag = custom.copyTag();
        if (tag.getStringOr(EXPANSION_TOOL_LOAN, "").isEmpty()
            || !tag.getStringOr(EXPANSION_TOOL_BELL, "").equals(Long.toString(c.village().bell().asLong()))) return null;
        return tag.getStringOr(EXPANSION_TOOL_SOURCE, "");
    }

    private static boolean hasExpansionLoan(ItemStack stack) {
        CustomData custom = stack == null || stack.isEmpty() ? null : stack.get(DataComponents.CUSTOM_DATA);
        return custom != null && !custom.copyTag().getStringOr(EXPANSION_TOOL_LOAN, "").isEmpty();
    }

    private static void markExpansionLoan(ItemStack stack, Ctx c, BlockPos source) {
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = custom == null ? new CompoundTag() : custom.copyTag();
        tag.putString(EXPANSION_TOOL_LOAN, "public");
        tag.putString(EXPANSION_TOOL_BELL, Long.toString(c.village().bell().asLong()));
        tag.putString(EXPANSION_TOOL_SOURCE, Long.toString(source.asLong()));
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static void clearExpansionLoan(ItemStack stack) {
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) return;
        CompoundTag tag = custom.copyTag();
        tag.remove(EXPANSION_TOOL_LOAN);
        tag.remove(EXPANSION_TOOL_BELL);
        tag.remove(EXPANSION_TOOL_SOURCE);
        if (tag.isEmpty()) stack.remove(DataComponents.CUSTOM_DATA);
        else stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static Container publicExpansionChest(Ctx c, BlockPos pos) {
        if (c == null || c.village() == null || pos == null || !c.level().hasChunkAt(pos)
            || !Lager.materialChests(c.level(), c.village()).contains(pos)) return null;
        return HopperBlockEntity.getContainerAt(c.level(), pos);
    }

    private static boolean returnExpansionTool(Ctx c, int inventorySlot) {
        if (c == null || c.village() == null || inventorySlot < 0
            || inventorySlot >= c.v().getInventory().getContainerSize()) return false;
        ItemStack borrowed = c.v().getInventory().getItem(inventorySlot);
        String sourceText = expansionLoanSource(borrowed, c);
        if (sourceText == null) return !hasExpansionLoan(borrowed);
        BlockPos source;
        try { source = BlockPos.of(Long.parseLong(sourceText)); }
        catch (RuntimeException ignored) { source = null; }

        List<BlockPos> stores = Lager.materialChests(c.level(), c.village());
        List<BlockPos> destinations = new java.util.ArrayList<>();
        if (source != null && stores.contains(source)) destinations.add(source);
        for (BlockPos pos : stores) if (!destinations.contains(pos)) destinations.add(pos);
        for (BlockPos pos : destinations) {
            Container box = publicExpansionChest(c, pos);
            if (box == null) continue;
            ItemStack returned = borrowed.copy();
            clearExpansionLoan(returned);
            ItemStack remaining = HopperBlockEntity.addItem(null, box, returned, null);
            if (remaining.isEmpty()) {
                c.v().getInventory().setItem(inventorySlot, ItemStack.EMPTY);
                c.v().getInventory().setChanged();
                box.setChanged();
                return true;
            }
        }
        return false;
    }

    private static void returnExpansionTools(Ctx c) {
        if (c == null || c.village() == null) return;
        for (int slot = 0; slot < c.v().getInventory().getContainerSize(); slot++) {
            ItemStack stack = c.v().getInventory().getItem(slot);
            if (expansionLoanSource(stack, c) != null) returnExpansionTool(c, slot);
        }
    }

    private static ExpansionToolLookup expansionTool(Ctx c, Roads.Repair task) {
        var inventory = c.v().getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            String source = expansionLoanSource(stack, c);
            if (source != null && matchesExpansionTool(stack, task))
                return new ExpansionToolLookup(new ExpansionTool(stack, slot), false);
        }
        // Return an incompatible borrowed tool before looking for the other tool type.
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (expansionLoanSource(inventory.getItem(slot), c) != null) returnExpansionTool(c, slot);
        }
        for (BlockPos pos : Lager.materialChests(c.level(), c.village())) {
            Container box = publicExpansionChest(c, pos);
            if (box == null) continue;
            for (int slot = 0; slot < box.getContainerSize(); slot++) {
                ItemStack sample = box.getItem(slot);
                if (!matchesExpansionTool(sample, task) || !sample.isDamageableItem()
                    || sample.getDamageValue() >= sample.getMaxDamage()) continue;
                ItemStack borrowed = sample.copyWithCount(1);
                ItemStack marked = borrowed.copy();
                markExpansionLoan(marked, c, pos);
                if (!inventory.canAddItem(marked)) {
                    if (!JobUtil.depositSurplus(c, item -> item.is(ItemTags.LOGS)
                        || item.is(ItemTags.SAPLINGS) || item.is(Items.STICK), 1, 0))
                        c.setActivity("braucht freien Inventarplatz für ein öffentliches Werkzeug");
                    return new ExpansionToolLookup(null, true);
                }
                if (!JobUtil.approachLong(c, pos, 3)) return new ExpansionToolLookup(null, true);
                ItemStack remainder = inventory.addItem(marked);
                if (!remainder.isEmpty()) return new ExpansionToolLookup(null, true);
                sample.shrink(1);
                if (sample.isEmpty()) box.setItem(slot, ItemStack.EMPTY);
                box.setChanged();
                inventory.setChanged();
                for (int carried = 0; carried < inventory.getContainerSize(); carried++) {
                    ItemStack stack = inventory.getItem(carried);
                    if (expansionLoanSource(stack, c) != null && matchesExpansionTool(stack, task))
                        return new ExpansionToolLookup(new ExpansionTool(stack, carried), false);
                }
            }
        }
        c.setActivity(task.action() == Roads.WorkAction.EXPANSION_GRADE_CUT
            ? "wartet auf eine öffentliche Schaufel für die Wohnfläche"
            : "wartet auf eine öffentliche Axt für die Wohnfläche");
        return new ExpansionToolLookup(null, true);
    }
    public java.util.function.Predicate<ItemStack> produce() {return s->s.is(net.minecraft.tags.ItemTags.LOGS)||s.is(net.minecraft.tags.ItemTags.SAPLINGS)||s.is(net.minecraft.world.item.Items.STICK);}
    public boolean primary(Ctx c) { return work(c) || Workshop.tick(c); }
    public boolean secondary(Ctx c) { return work(c); }

    public static boolean work(Ctx c) {
        return c.st().roadBuilding?construct(c):maintain(c)||construct(c);
    }

    public static boolean construct(Ctx c) {
        if(c.village()==null)return false;
        var st=c.st();
        if(st.roadRepair==null)returnExpansionTools(c);
        if(st.roadRepair==null) {
            if(c.now()<st.roadBuildScanAt)return false;
            st.roadRepair=Roads.nextConstruction(c.level(),c.village());
            st.roadBuildScanAt=c.now()+(Roads.expansionPreparationActive(c.village())?40:200);
            st.roadBuilding=st.roadRepair!=null;
            if(st.roadRepair==null)returnExpansionTools(c);
        }
        return st.roadBuilding&&perform(c);
    }

    public static boolean maintain(Ctx c) {
        if(c.village()==null)return false;
        var st=c.st();
        if(st.roadRepair==null)returnExpansionTools(c);
        if(st.roadBuilding)return false;
        if(st.roadRepair==null) {
            if(c.now()<st.roadScanAt)return de.villagerplus.village.RailMaintenance.work(c);
            st.roadScanAt=c.now()+200;
            st.roadRepair=Roads.nextRepair(c.level(),c.village());
            if(st.roadRepair==null)st.roadRepair=RoadMaintenance.nextBridgeRepair(c.level(),c.village());
            if(st.roadRepair==null)return de.villagerplus.village.RailMaintenance.work(c);
        }
        return perform(c);
    }

    private static boolean perform(Ctx c) {
        var st=c.st();Roads.Repair task=st.roadRepair;if(task==null){st.roadBuilding=false;return false;}
        boolean constructing=st.roadBuilding;
        if(!expansionCut(task))returnExpansionTools(c);
        if(!Roads.repairable(c.level(),task)) {
            if(expansionCut(task))returnExpansionTools(c);
            Anim.cancelBreaking(c.v());st.roadRepair=null;st.roadBuilding=false;st.roadScanAt=0;
            if(constructing) {
                // Another builder may have completed this lane while we carried its material.
                // Re-read the saved cursor/world now; never replay the stale block operation.
                st.roadBuildScanAt=0;
                continueConstruction(c,st,true);
                if(st.roadBuilding)return perform(c);
            }
            return false;
        }
        var required=requiredMaterial(task);
        if(required!=null) {
            MaterialResult material=ensureMaterial(c,task,required);
            if(material==MaterialResult.WAIT)return true;
            if(material==MaterialResult.NONE) {st.roadRepair=null;st.roadBuilding=false;return false;}
        }
        if(task.removal()!=null) {
            c.setActivity(task.action()==Roads.WorkAction.EXPANSION_CLEAR?"rodet die neue Wohnfläche":
                task.action()==Roads.WorkAction.EXPANSION_GRADE_CUT?"ebnet die neue Wohnfläche":
                task.action()==Roads.WorkAction.GRADE_CUT?"trägt die Straßenböschung ab":
                task.action()==Roads.WorkAction.GAP_CLEAR?"räumt eine natürliche Wegblockade":"rodet den Straßenkorridor");
            if(!JobUtil.approachLong(c,task.worksite(),3)) {
                if(st.cooldownUntil>c.now()){Anim.cancelBreaking(c.v());st.roadRepair=null;st.roadBuilding=false;}
                return true;
            }
            if(!c.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new net.minecraft.world.phys.AABB(task.ground()),e->e.isAlive()).isEmpty())return true;
            ExpansionTool workingTool=null;
            if(expansionCut(task)) {
                ExpansionToolLookup lookup=expansionTool(c,task);
                if(lookup.waiting()||lookup.tool()==null)return true;
                workingTool=lookup.tool();
            }
            var tool=workingTool!=null?workingTool.stack().getItem():
                task.action()==Roads.WorkAction.GRADE_CUT||task.action()==Roads.WorkAction.GAP_CLEAR
                    ?Items.IRON_SHOVEL:Items.IRON_AXE;
            ItemStack dropsTool=workingTool==null?new ItemStack(tool):workingTool.stack();
            var drops=net.minecraft.world.level.block.Block.getDrops(task.removal(),c.level(),task.ground(),null,c.v(),dropsTool);
            if(!Woodcutting.canFitAllDrops(c.v().getInventory(),drops)) {
                if(!JobUtil.depositSurplus(c,s->s.is(net.minecraft.tags.ItemTags.LOGS)
                    ||s.is(net.minecraft.tags.ItemTags.SAPLINGS)||s.is(Items.STICK),1,0))
                    c.setActivity("braucht freien Inventarplatz für das Rodungsholz");
                return true;
            }
            if(!Anim.breakStep(c,task.ground(),task.removal(),tool))return true;
            boolean removed=c.level().setBlock(task.ground(),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),3);
            if(removed) {
                for(ItemStack drop:drops)JobUtil.give(c.level(),c.v(),drop);
                if(workingTool!=null&&workingTool.stack().isDamageableItem()) {
                    workingTool.stack().hurtAndBreak(1,c.level(),null,ignored->{ });
                    c.v().getInventory().setChanged();
                }
                Anim.blockBreak(c.level(),task.ground(),task.removal());c.mem().jobsDone++;
                if(task.action()==Roads.WorkAction.GRADE_CUT)Roads.noteGradeWork(c.level(),task);
                st.roadScanAt=0;
            }
            st.roadRepair=null;st.roadBuilding=false;st.roadBuildScanAt=0;
            if(removed)continueConstruction(c,st,constructing);
            return true;
        }
        CurbPlan curb=curbPlan(c,task);
        if(curb!=null) {
            var item=curb.repair().paving().getBlock().asItem();
            if(JobUtil.count(c.v().getInventory(),s->s.is(item))==0
                &&!c.v().getInventory().canAddItem(new ItemStack(item))) {
                curb=null;
            } else {
                MaterialResult material=ensureMaterial(c,task,item);
                if(material==MaterialResult.WAIT)return true;
                if(material==MaterialResult.NONE)curb=null;
            }
        }
        c.setActivity(task.action()==Roads.WorkAction.GRADE_FILL?"schüttet den Straßenunterbau auf":
            task.action()==Roads.WorkAction.GRADE_PAVE?"pflastert das Straßenprofil":
            task.completionKey()!=null?"setzt Straßenbeleuchtung":st.roadBuilding?"baut die Überlandstraße":"bessert die Überlandstraße aus");
        if(!JobUtil.approachLong(c,task.worksite(),3)) {
            if(st.cooldownUntil>c.now())st.roadRepair=null;
            return true;
        }
        boolean done=repair(c,task);
        if(done&&curb!=null)placeCurb(c,curb);
        if(done&&task.completionKey()!=null&&task.action()!=Roads.WorkAction.GAP_CLEAR&&c.village()!=null) {
            var roads=new java.util.HashMap<>(c.village().bellEntity().getAttachedOrElse(VillagerPlus.ROADS,java.util.Map.of()));
            roads.put(task.completionKey(),1L);
            c.village().bellEntity().setAttached(VillagerPlus.ROADS,java.util.Map.copyOf(roads));c.village().dirty();
        }
        st.roadRepair=null;st.roadBuilding=false;
        if(done) {
            st.roadBuildScanAt=0;st.roadScanAt=0;
            continueConstruction(c,st,constructing);
        }
        return done;
    }

    /** A one-item, lower-slab shoulder just outside each paid three-lane road section. */
    private record CurbPlan(Roads.Repair repair) { }

    private static CurbPlan curbPlan(Ctx c,Roads.Repair road) {
        if(c.village()==null||road==null||road.gradeKey()==null||road.gradeCursor()<0
            ||road.action()!=Roads.WorkAction.GRADE_PAVE||road.ownerBell()==null
            ||!road.ownerBell().equals(c.village().bell()))return null;
        var paving=road.paving().getBlock();
        var slab=paving==Blocks.SANDSTONE?Blocks.SANDSTONE_SLAB:
            paving==Blocks.COBBLESTONE?Blocks.COBBLESTONE_SLAB:null;
        if(slab==null)return null;

        var saved=c.village().bellEntity().getAttachedOrElse(VillagerPlus.ROADS,java.util.Map.of());
        Long fromPacked=saved.get(road.gradeKey()+":from"),toPacked=saved.get(road.gradeKey()+":to");
        if(fromPacked==null||toPacked==null)return null;
        BlockPos from=BlockPos.of(fromPacked),to=BlockPos.of(toPacked);
        for(int side:new int[]{-1,1}) {
            BlockPos lane=Roads.laneCell(saved,road.gradeKey(),from,to,road.gradeCursor(),side);
            if(lane.getX()!=road.ground().getX()||lane.getZ()!=road.ground().getZ())continue;
            BlockPos center=Roads.laneCell(saved,road.gradeKey(),from,to,road.gradeCursor(),0);
            int outwardX=lane.getX()-center.getX(),outwardZ=lane.getZ()-center.getZ();
            if(Math.abs(outwardX)+Math.abs(outwardZ)!=1)return null;
            BlockPos target=new BlockPos(lane.getX()+outwardX,road.gradeY(),lane.getZ()+outwardZ);
            BlockState slabState=slab.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.BOTTOM);
            if(c.level().hasChunkAt(target)&&c.level().getBlockState(target).is(slab))return null;
            if(!c.level().hasChunkAt(target)||!c.level().getBlockState(target).isAir()
                ||Lighting.protectedVillageCell(c.level(),c.village(),target)
                ||Lighting.protectedVillageCell(c.level(),c.village(),target.below()))return null;
            Roads.Repair curb=new Roads.Repair(target,slabState,road.worksite(),false,null,null,null,
                road.ownerBell(),road.peerBell(),Roads.WorkAction.GRADE_PAVE,road.gradeKey(),road.gradeCursor(),road.gradeY());
            if(!Roads.repairable(c.level(),curb))return null;
            return new CurbPlan(curb);
        }
        return null;
    }

    private static boolean placeCurb(Ctx c,CurbPlan plan) {
        Roads.Repair curb=plan.repair();
        if(!c.level().getBlockState(curb.ground()).isAir()||!Roads.repairable(c.level(),curb)
            ||!c.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                new net.minecraft.world.phys.AABB(curb.ground()),e->e.isAlive()).isEmpty())return false;
        var item=curb.paving().getBlock().asItem();
        if(JobUtil.count(c.v().getInventory(),s->s.is(item))<1
            ||!c.level().setBlock(curb.ground(),curb.paving(),3))return false;
        JobUtil.take(c.v().getInventory(),s->s.is(item),1);
        Anim.hold(c,item);JobUtil.swing(c.v());
        Anim.blockPlace(c.level(),curb.ground(),curb.paving(),1.0F,1.0F);
        c.mem().jobsDone++;
        Roads.noteGradeWork(c.level(),curb);
        return true;
    }

    /** Keep a paid route section moving from one persisted lane task to the next. */
    private static void continueConstruction(Ctx c,JobState st,boolean constructing) {
        if(!constructing||c.village()==null)return;
        st.roadRepair=Roads.nextConstruction(c.level(),c.village());
        st.roadBuilding=st.roadRepair!=null;
    }

    private static net.minecraft.world.item.Item requiredMaterial(Roads.Repair task) {
        if(task.action()==Roads.WorkAction.CLEAR||task.action()==Roads.WorkAction.EXPANSION_CLEAR
            ||task.action()==Roads.WorkAction.EXPANSION_GRADE_CUT)return null;
        if(task.removal()!=null&&task.action()!=Roads.WorkAction.GRADE_CUT)return null;
        if(task.ownerBell()!=null&&task.peerBell()==null&&task.paving().is(Blocks.DIRT_PATH))return Items.DIRT;
        return task.paving().getBlock().asItem();
    }

    private static MaterialResult ensureMaterial(Ctx c,Roads.Repair task,net.minecraft.world.item.Item item) {
        if(JobUtil.count(c.v().getInventory(),s->s.is(item))>0)return MaterialResult.READY;
        for(BlockPos p:Lager.materialChests(c.level(),c.village())) {
            boolean privateNeighbor=false;
            for(var d:net.minecraft.core.Direction.Plane.HORIZONTAL) {
                var be=c.level().getBlockEntity(p.relative(d));
                if(be instanceof net.minecraft.world.level.block.entity.ChestBlockEntity&&be.getAttached(VillagerPlus.OWNER)!=null)privateNeighbor=true;
            }
            if(privateNeighbor)continue;
            var box=HopperBlockEntity.getContainerAt(c.level(),p);
            if(box==null||JobUtil.count(box,s->s.is(item))==0)continue;
            c.setActivity("holt Straßenbaumaterial");
            if(!JobUtil.approachLong(c,p,3)) {
                if(c.st().cooldownUntil>c.now()){Anim.cancelBreaking(c.v());c.st().roadRepair=null;c.st().roadBuilding=false;}
                return MaterialResult.WAIT;
            }
            for(int slot=0;slot<box.getContainerSize();slot++) {
                ItemStack sample=box.getItem(slot);
                if(!sample.is(item))continue;
                ItemStack taken=sample.copyWithCount(1);
                if(!c.v().getInventory().canAddItem(taken))return MaterialResult.WAIT;
                sample.shrink(1);
                if(sample.isEmpty())box.setItem(slot,ItemStack.EMPTY);
                c.v().getInventory().addItem(taken);box.setChanged();return MaterialResult.WAIT;
            }
        }
        return MaterialResult.NONE;
    }

    static boolean repair(Ctx c,Roads.Repair task) {
        if(c.v().position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(task.worksite()))>9
            ||!Roads.repairable(c.level(),task))return false;
        if(!c.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new net.minecraft.world.phys.AABB(task.ground()),e->e.isAlive()).isEmpty())return false;
        var item=requiredMaterial(task);
        if(item==null)return false;
        if(JobUtil.count(c.v().getInventory(),s->s.is(item))<1)return false;
        if(!c.level().setBlock(task.ground(),task.paving(),3))return false;
        JobUtil.take(c.v().getInventory(),s->s.is(item),1);
        Anim.hold(c,item);JobUtil.swing(c.v());
        Anim.blockPlace(c.level(),task.ground(),task.paving(),1.0F,1.0F);
        c.mem().jobsDone++;
        if(task.action()==Roads.WorkAction.GRADE_FILL||task.action()==Roads.WorkAction.GRADE_PAVE)Roads.noteGradeWork(c.level(),task);
        return true;
    }
}
