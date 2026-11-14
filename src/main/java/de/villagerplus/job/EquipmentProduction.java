package de.villagerplus.job;

import de.villagerplus.build.Blueprints;
import de.villagerplus.village.Lager;
import de.villagerplus.village.Specials;
import de.villagerplus.village.StockPolicy;
import de.villagerplus.village.Villages;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/** Loaded recipes, physical ingredient pickup, workbench crafting and toolshed delivery. */
final class EquipmentProduction {
    private static final List<TagKey<Item>> KINDS = List.of(ItemTags.PICKAXES,ItemTags.AXES,ItemTags.SHOVELS,ItemTags.HOES,ItemTags.SWORDS);
    private static final List<EquipmentSlot> ARMOR_SLOTS = List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET);
    private static final List<Item> SIDEARM_KINDS = List.of(Items.BOW,Items.CROSSBOW,Items.SHIELD);
    private static final List<Item> DURABLE_UTILITY_KINDS = List.of(Items.SHEARS,Items.FISHING_ROD,Items.FLINT_AND_STEEL,Items.BRUSH);
    private EquipmentProduction() { }

    private record CraftingPlan(CraftingRecipe recipe,CraftingInput input,ItemStack output,int deficit,int preference) { }

    static boolean work(Ctx c) {
        if(c.village()==null || !Specials.has(c.village(),Blueprints.TOOLSHED)) return false;
        Container inv=c.v().getInventory();
        ItemStack carried=firstEquipment(inv);
        if(!carried.isEmpty()) {
            if(Specials.depotWithRoom(c.level(),c.village(),Blueprints.TOOLSHED,carried)==null) return false;
            holdWithoutReplacingEquippedMainhand(c,carried.getItem());
            // JobUtil rechecks the stock target and destination capacity on arrival; a full
            // toolshed leaves the item safely carried until a slot becomes available.
            return JobUtil.depositSurplus(c,EquipmentProduction::deliverableEquipment,1,0);
        }
        BlockPos bench=bench(c);
        if(bench==null)return false;
        List<Container> depots=Specials.containers(c.level(),c.village(),Blueprints.TOOLSHED);
        if(depots.isEmpty())return false;
        List<Villager> residents=Villages.residents(c.level(),c.village(),96,v->!v.isBaby());
        int reserve=Math.max(1,Math.min(8,(residents.size()+7)/8));
        Map<TagKey<Item>,Integer> familyDeficits=new HashMap<>();
        for(TagKey<Item> kind:KINDS)familyDeficits.put(kind,Math.max(0,reserve-usable(depots,residents,kind)));
        Map<EquipmentSlot,Integer> armorDeficits=new java.util.EnumMap<>(EquipmentSlot.class);
        for(EquipmentSlot slot:ARMOR_SLOTS)armorDeficits.put(slot,Math.max(0,reserve-usableArmor(depots,residents,slot)));
        Map<Item,Integer> sidearmDeficits=new HashMap<>();
        for(Item item:SIDEARM_KINDS)sidearmDeficits.put(item,Math.max(0,reserve-usableSidearm(depots,residents,item)));
        Map<Item,Integer> utilityDeficits=new HashMap<>();
        for(Item item:DURABLE_UTILITY_KINDS)utilityDeficits.put(item,Math.max(0,reserve-usableUtility(depots,residents,item)));
        if(familyDeficits.values().stream().allMatch(deficit->deficit==0)
            &&armorDeficits.values().stream().allMatch(deficit->deficit==0)
            &&sidearmDeficits.values().stream().allMatch(deficit->deficit==0)
            &&utilityDeficits.values().stream().allMatch(deficit->deficit==0))return false;
        List<BlockPos> sources=Lager.materialChests(c.level(),c.village());
        Container available=inv;
        for(BlockPos pos:sources) {
            Container source=HopperBlockEntity.getContainerAt(c.level(),pos);
            if(source!=null)available=new CompoundContainer(available,source);
        }
        Container safeAvailable=safeIngredientView(available);
        List<CraftingPlan> plans=new ArrayList<>();
        for(var holder:c.level().recipeAccess().getRecipes()) {
            if(holder.value() instanceof CraftingRecipe recipe) {
                CraftingInput input=RecipeCrafting.inputFor(recipe,safeAvailable,c.level());
                if(input==null||equipmentIngredient(input))continue;
                ItemStack output=recipe.assemble(input);
                if(!replacementOutput(output,familyDeficits,armorDeficits,sidearmDeficits,utilityDeficits)
                    ||!reserveNeeded(depots,residents,reserve,output)
                    ||!canDeliver(c,depots,residents,output))continue;
                plans.add(new CraftingPlan(recipe,input,output,
                    replacementDeficit(output,familyDeficits,armorDeficits,sidearmDeficits,utilityDeficits),preference(output)));
            }
        }
        // Fill the greatest usable-stock gap first, with stable tie breaks across loaded recipes.
        plans.sort(Comparator.comparingInt(CraftingPlan::deficit).reversed()
            .thenComparingInt(CraftingPlan::preference)
            .thenComparing(plan->BuiltInRegistries.ITEM.getKey(plan.output().getItem()).toString()));
        for(CraftingPlan plan:plans) {
            CraftingRecipe recipe=plan.recipe();
            CraftingInput input=plan.input();
            ItemStack output=plan.output();
            // Each pickup is re-planned from real inventories. Interruptions/reloads leave all carried items intact.
            for(ItemStack ingredient:input.items()) {
                if(ingredient.isEmpty())continue;
                int required=0;
                for(ItemStack part:input.items())if(ItemStack.isSameItemSameComponents(part,ingredient))required+=part.getCount();
                int own=JobUtil.count(inv,s->ItemStack.isSameItemSameComponents(s,ingredient));
                if(own>=required)continue;
                for(BlockPos pos:sources) {
                    Container source=HopperBlockEntity.getContainerAt(c.level(),pos);
                    if(source==null)continue;
                    for(int slot=0;slot<source.getContainerSize();slot++) {
                        ItemStack stack=source.getItem(slot);
                        if(stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack,ingredient))continue;
                        c.setActivity("holt Zutaten für Ersatz-Ausrüstung");holdWithoutReplacingEquippedMainhand(c,stack.getItem());
                        if(!JobUtil.approachLong(c,pos,3.0))return true;
                        ItemStack moving=stack.copyWithCount(Math.min(required-own,stack.getCount()));
                        int before=moving.getCount();
                        ItemStack rest=HopperBlockEntity.addItem(null,inv,moving,null);
                        int moved=before-rest.getCount();
                        if(moved==0)return false;
                        stack.shrink(moved);source.setChanged();inv.setChanged();
                        JobUtil.swing(c.v());c.st().cooldownUntil=c.now()+40;return true;
                    }
                }
                return false;
            }
            c.setActivity("fertigt Ausrüstung an der Werkbank");
            if(!JobUtil.approachLong(c,bench,2.8))return true;
            // A second smith may have filled the toolshed while this villager walked. Recheck the
            // reserve and exact destination room at the recipe commit point; RecipeCrafting leaves
            // ingredients untouched if the output no longer fits the villager inventory.
            return craftVerifiedOutput(c,bench,recipe,output);
        }
        return false;
    }

    /** Re-evaluates the selected loaded recipe at the bench and commits its exact assembled output atomically. */
    private static boolean craftVerifiedOutput(Ctx c,BlockPos bench,CraftingRecipe recipe,ItemStack expected) {
        if(!c.level().hasChunkAt(bench)||!c.level().getBlockState(bench).is(Blocks.CRAFTING_TABLE))return false;
        Container inventory=c.v().getInventory();
        CraftingInput input=RecipeCrafting.inputFor(recipe,safeIngredientView(inventory),c.level());
        if(input==null)return false;
        ItemStack output=recipe.assemble(input);
        if(output.isEmpty()||output.getCount()!=expected.getCount()
            ||!ItemStack.isSameItemSameComponents(output,expected)
            ||equipmentIngredient(input)||!safeReplacementOutput(output))return false;

        List<Container> currentDepots=Specials.containers(c.level(),c.village(),Blueprints.TOOLSHED);
        List<Villager> currentResidents=Villages.residents(c.level(),c.village(),96,v->!v.isBaby());
        int currentReserve=Math.max(1,Math.min(8,(currentResidents.size()+7)/8));
        if(!reserveNeeded(currentDepots,currentResidents,currentReserve,output)
            ||!canDeliver(c,currentDepots,currentResidents,output))return false;

        if(!RecipeCrafting.commit(inventory,input,output,recipe.getRemainingItems(input)))return false;
        c.setActivity("fertigt Ausrüstung an der Werkbank");
        holdWithoutReplacingEquippedMainhand(c,output.getItem());
        c.st().cooldownUntil=c.now()+100;
        c.mem().jobsDone++;
        return true;
    }

    private static int preference(ItemStack output) {
        if(output.isEmpty())return 3;
        String path=BuiltInRegistries.ITEM.getKey(output.getItem()).getPath();
        return path.startsWith("iron_")?0:path.startsWith("stone_")?1:2;
    }
    private static int replacementDeficit(ItemStack output,Map<TagKey<Item>,Integer> familyDeficits,
            Map<EquipmentSlot,Integer> armorDeficits,Map<Item,Integer> sidearmDeficits,Map<Item,Integer> utilityDeficits) {
        if(output.isEmpty())return 0;
        int deficit=0;
        for(TagKey<Item> kind:KINDS) {
            if(output.is(kind))deficit=Math.max(deficit,familyDeficits.getOrDefault(kind,0));
        }
        EquipmentSlot armorSlot=armorSlot(output);
        Integer armorDeficit=armorSlot==null?null:armorDeficits.get(armorSlot);
        deficit=Math.max(deficit,armorDeficit==null?0:armorDeficit);
        for(Item item:SIDEARM_KINDS)if(output.is(item))deficit=Math.max(deficit,sidearmDeficits.getOrDefault(item,0));
        for(Item item:DURABLE_UTILITY_KINDS)if(output.is(item))deficit=Math.max(deficit,utilityDeficits.getOrDefault(item,0));
        return deficit;
    }
    private static boolean equipment(ItemStack stack) {
        return !stack.isEmpty() && StockPolicy.category(stack.getItem())==StockPolicy.Category.TOOLS;
    }
    private static boolean deliverableEquipment(ItemStack stack) {
        return equipment(stack)&&isUsable(stack)&&!stack.isEnchanted();
    }
    private static boolean safeReplacementOutput(ItemStack stack) {
        return equipment(stack)&&isUsable(stack)&&!stack.isEnchanted();
    }
    private static Container safeIngredientView(Container source) {
        SimpleContainer safe=new SimpleContainer(source.getContainerSize());
        for(int slot=0;slot<source.getContainerSize();slot++) {
            ItemStack stack=source.getItem(slot);
            // Recipes with broad tags must never consume existing gear or any enchanted stack.
            if(!stack.isEmpty()&&!equipment(stack)&&!stack.isEnchanted())safe.setItem(slot,stack.copy());
        }
        return safe;
    }
    private static boolean equipmentIngredient(CraftingInput input) {
        for(ItemStack ingredient:input.items())if(equipment(ingredient)||ingredient.isEnchanted())return true;
        return false;
    }
    private static void holdWithoutReplacingEquippedMainhand(Ctx c,Item item) {
        ItemStack mainhand=c.v().getItemBySlot(EquipmentSlot.MAINHAND);
        if(!mainhand.isEmpty()&&(equipment(mainhand)||mainhand.isEnchanted())) {
            // Anim.hold is a visual copy routed through MAINHAND. Preserve real equipped gear instead.
            c.st().tool=null;
            c.st().toolUntil=0;
            c.st().holding=false;
            return;
        }
        Anim.hold(c,item);
    }
    static EquipmentSlot armorSlot(ItemStack stack) {
        if(stack.isEmpty()||!stack.has(DataComponents.EQUIPPABLE))return null;
        Equippable equippable=stack.get(DataComponents.EQUIPPABLE);
        if(equippable==null)return null;
        EquipmentSlot slot=equippable.slot();
        return ARMOR_SLOTS.contains(slot)?slot:null;
    }
    private static ItemStack firstEquipment(Container inv) {
        for(int i=0;i<inv.getContainerSize();i++)if(deliverableEquipment(inv.getItem(i)))return inv.getItem(i);
        return ItemStack.EMPTY;
    }
    private static int usable(List<Container> boxes,List<Villager> residents,TagKey<Item> kind) {
        long count=0;
        for(Container box:boxes)for(int i=0;i<box.getContainerSize();i++) {
            ItemStack item=box.getItem(i);
            if(!item.isEmpty()&&item.is(kind)&&isUsable(item))count+=item.getCount();
        }
        for(Villager villager:residents) {
            count+=JobUtil.count(villager.getInventory(),item->item.is(kind)&&isUsable(item));
            for(EquipmentSlot hand:List.of(EquipmentSlot.MAINHAND,EquipmentSlot.OFFHAND)) {
                ItemStack equipped=villager.getItemBySlot(hand);
                if(!equipped.isEmpty()&&equipped.is(kind)&&isUsable(equipped))count+=equipped.getCount();
            }
        }
        return (int)Math.min(Integer.MAX_VALUE,count);
    }

    private static int usableArmor(List<Container> boxes,List<Villager> residents,EquipmentSlot slot) {
        long count=0;
        for(Container box:boxes)for(int i=0;i<box.getContainerSize();i++) {
            ItemStack item=box.getItem(i);
            if(!item.isEmpty()&&armorSlot(item)==slot&&isUsable(item))count+=item.getCount();
        }
        for(Villager villager:residents) {
            count+=JobUtil.count(villager.getInventory(),item->armorSlot(item)==slot&&isUsable(item));
            ItemStack worn=villager.getItemBySlot(slot);
            if(!worn.isEmpty()&&armorSlot(worn)==slot&&isUsable(worn))count+=worn.getCount();
        }
        return (int)Math.min(Integer.MAX_VALUE,count);
    }

    private static int usableSidearm(List<Container> boxes,List<Villager> residents,Item item) {
        long count=0;
        for(Container box:boxes)for(int i=0;i<box.getContainerSize();i++) {
            ItemStack stack=box.getItem(i);
            if(!stack.isEmpty()&&stack.is(item)&&isUsable(stack))count+=stack.getCount();
        }
        for(Villager villager:residents) {
            count+=JobUtil.count(villager.getInventory(),stack->stack.is(item)&&isUsable(stack));
            for(EquipmentSlot hand:List.of(EquipmentSlot.MAINHAND,EquipmentSlot.OFFHAND)) {
                ItemStack equipped=villager.getItemBySlot(hand);
                if(!equipped.isEmpty()&&equipped.is(item)&&isUsable(equipped))count+=equipped.getCount();
            }
        }
        return (int)Math.min(Integer.MAX_VALUE,count);
    }

    private static int usableUtility(List<Container> boxes,List<Villager> residents,Item item) {
        long count=0;
        for(Container box:boxes)for(int i=0;i<box.getContainerSize();i++) {
            ItemStack stack=box.getItem(i);
            if(!stack.isEmpty()&&stack.is(item)&&isUsable(stack))count+=stack.getCount();
        }
        for(Villager villager:residents) {
            count+=JobUtil.count(villager.getInventory(),stack->stack.is(item)&&isUsable(stack));
            for(EquipmentSlot hand:List.of(EquipmentSlot.MAINHAND,EquipmentSlot.OFFHAND)) {
                ItemStack equipped=villager.getItemBySlot(hand);
                if(!equipped.isEmpty()&&equipped.is(item)&&isUsable(equipped))count+=equipped.getCount();
            }
        }
        return (int)Math.min(Integer.MAX_VALUE,count);
    }

    private static boolean replacementOutput(ItemStack output,Map<TagKey<Item>,Integer> familyDeficits,
            Map<EquipmentSlot,Integer> armorDeficits,Map<Item,Integer> sidearmDeficits,Map<Item,Integer> utilityDeficits) {
        if(!safeReplacementOutput(output))return false;
        for(TagKey<Item> kind:KINDS)if(output.is(kind)&&familyDeficits.getOrDefault(kind,0)>0)return true;
        EquipmentSlot slot=armorSlot(output);
        if(slot!=null&&armorDeficits.getOrDefault(slot,0)>0)return true;
        return sidearmDeficits.getOrDefault(output.getItem(),0)>0
            ||utilityDeficits.getOrDefault(output.getItem(),0)>0;
    }

    private static boolean isUsable(ItemStack stack) {
        return StockPolicy.isServiceableEquipment(stack);
    }

    private static boolean reserveNeeded(List<Container> depots,List<Villager> residents,int reserve,ItemStack output) {
        for(TagKey<Item> kind:KINDS)if(output.is(kind)&&usable(depots,residents,kind)<reserve)return true;
        EquipmentSlot slot=armorSlot(output);
        if(slot!=null&&usableArmor(depots,residents,slot)<reserve)return true;
        if(SIDEARM_KINDS.contains(output.getItem()))return usableSidearm(depots,residents,output.getItem())<reserve;
        return DURABLE_UTILITY_KINDS.contains(output.getItem())
            &&usableUtility(depots,residents,output.getItem())<reserve;
    }

    private static boolean canDeliver(Ctx c,List<Container> depots,List<Villager> residents,ItemStack output) {
        return output.getCount()<=availableDeliveryRoom(c,residents,output.getItem())
            &&fits(depots,output);
    }

    /** Includes carried serviceable equipment so two smiths cannot spend one target slot twice before delivery. */
    private static int availableDeliveryRoom(Ctx c,List<Villager> residents,Item item) {
        int room=StockPolicy.deliveryRoom(c.level(),c.village(),item);
        if(room==Integer.MAX_VALUE)return room;
        int pending=0;
        for(Villager villager:residents) {
            // Only serviceable carried equipment is a pending delivery. Worn gear may be
            // awaiting repair/replacement and must not reserve the sole target slot for a
            // usable replacement.
            int carried=JobUtil.count(villager.getInventory(),stack->stack.is(item)&&isUsable(stack));
            pending+=Math.min(room-pending,carried);
            if(pending>=room)return 0;
        }
        return room-pending;
    }

    /** Simulates vanilla insertion against copies so partial and stacked capacity follow the real container rules. */
    private static boolean fits(List<Container> depots,ItemStack offered) {
        ItemStack remaining=offered.copy();
        for(Container depot:depots) {
            SimpleContainer preview=new SimpleContainer(depot.getContainerSize());
            for(int slot=0;slot<depot.getContainerSize();slot++)preview.setItem(slot,depot.getItem(slot).copy());
            remaining=HopperBlockEntity.addItem(null,preview,remaining,null);
            if(remaining.isEmpty())return true;
        }
        return false;
    }
    private static BlockPos bench(Ctx c) {
        BlockPos origin=Specials.get(c.village(),Blueprints.TOOLSHED);
        BlockPos cached=c.st().toolCraftBench;
        if(cached!=null && Math.abs(cached.getX()-origin.getX())<=8 && Math.abs(cached.getZ()-origin.getZ())<=8
            && c.level().hasChunkAt(cached)&&c.level().getBlockState(cached).is(Blocks.CRAFTING_TABLE))return cached;
        if(c.now()<c.st().toolCraftScanAt)return null;
        c.st().toolCraftScanAt=c.now()+200;c.st().toolCraftBench=null;
        for(BlockPos pos:BlockPos.betweenClosed(origin.offset(-8,-2,-8),origin.offset(8,6,8))) {
            if(c.level().hasChunkAt(pos)&&c.level().getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                c.st().toolCraftBench=pos.immutable();return c.st().toolCraftBench;
            }
        }
        return null;
    }
}
