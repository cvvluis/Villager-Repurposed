package de.villagerplus.village;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.block.DorfschildBlock;
import de.villagerplus.block.ModBlocks;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Feste Haeuser: Jeder Dorfbewohner hat einen Namen und sein Haus bekommt ein Schild mit Name und Beruf. */
public final class Housing {
	private Housing() {
	}

	public static void update(ServerLevel level, Villager v, VillagerMemory mem) {
		ensureName(v, mem);
		trackHome(level, v, mem);
		if (mem.homeBed.isPresent() && (mem.homeDoor.isEmpty()
			|| !(level.getBlockState(mem.homeDoor.get()).getBlock() instanceof DoorBlock))) {
			mem.homeDoor = findDoor(level, mem.homeBed.get());
			mem.signPos = Optional.empty();
		}
		// Record demand even before Vanilla assigns a bed. The persisted request lets the
		// normal Housing tick retry as soon as this resident gets a valid home.
		PrivateChestProvisioning.ensure(level, v, mem, Villages.find(level, v));
		if (mem.homeBed.isEmpty() || mem.homeDoor.isEmpty()) {
			return;
		}
		// Das Schild aendert sich selten: ohne Schild sofort, sonst nur etwa alle 1200 Ticks pruefen (die Mitbewohner-Abfrage ist teuer).
		if (mem.signPos.isEmpty() || (level.getGameTime() / de.villagerplus.Config.HOUSING_INTERVAL + v.getId()) % 6 == 0) {
			updateSign(level, v, mem);
		}
	}

	public static boolean hasHome(Villager v) {
		return v.getBrain().getMemory(MemoryModuleType.HOME).isPresent();
	}

	private static void ensureName(Villager v, VillagerMemory mem) {
		if (v.hasCustomName()) {
			mem.name = v.getCustomName().getString();
			return;
		}
		if (mem.name.isEmpty()) {
			mem.name = uniqueName(v);
		}
		v.setCustomName(Component.literal(mem.name));
		v.setCustomNameVisible(false);
	}

	/** Das Bett, das die Vanilla-KI dem Dorfbewohner zugewiesen hat, wird zum festen Zuhause. */
	private static void trackHome(ServerLevel level, Villager v, VillagerMemory mem) {
		Optional<GlobalPos> home = v.getBrain().getMemory(MemoryModuleType.HOME);
		if (home.isPresent() && home.get().dimension() == level.dimension()) {
			BlockPos bed = home.get().pos();
			if (mem.homeBed.isEmpty() || !mem.homeBed.get().equals(bed)) {
				mem.homeBed = Optional.of(bed);
				mem.homeDoor = Optional.empty();
				// Keep the prior chest pointer until PrivateChestProvisioning checks its owner and
				// either accepts it, migrates it safely, or clears the stale pointer. Clearing it
				// here loses the only durable route to an old chest beyond the new home's scan.
				mem.signPos = Optional.empty();
				mem.signText = "";
			}
		}
	}

	private static Optional<BlockPos> findDoor(ServerLevel level, BlockPos bed) {
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos p : BlockPos.betweenClosed(bed.offset(-8, -2, -8), bed.offset(8, 3, 8))) {
			BlockState s = level.getBlockState(p);
			if (s.getBlock() instanceof DoorBlock d && d.type().canOpenByHand() && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
				double dist = p.distSqr(bed);
				if (dist < bestDist) {
					best = p.immutable();
					bestDist = dist;
				}
			}
		}
		return Optional.ofNullable(best);
	}

	private static void updateSign(ServerLevel level, Villager v, VillagerMemory mem) {
		BlockPos door = mem.homeDoor.get();
		List<Villager> residents = level.getEntitiesOfClass(Villager.class, new net.minecraft.world.phys.AABB(door).inflate(24),
			o -> o.isAlive() && o.getAttachedOrCreate(VillagerPlus.MEMORY).homeDoor.map(door::equals).orElse(false));
		// Nur ein Bewohner (kleinste UUID) pflegt das gemeinsame Schild.
		if (residents.stream().min(Comparator.comparing(o -> o.getUUID())).map(o -> o != v).orElse(false)) {
			return;
		}

		if (mem.signPos.isPresent() && level.getBlockState(mem.signPos.get()).getBlock() instanceof WallSignBlock) {
			// Altes Vanilla-Schild durch das Hausschild ersetzen (gleiche Stelle, gleiche Richtung).
			BlockPos old = mem.signPos.get();
			Direction out = level.getBlockState(old).getValue(WallSignBlock.FACING);
			level.setBlock(old, ModBlocks.DORFSCHILD.defaultBlockState().setValue(DorfschildBlock.FACING, out), 3);
			mem.signText = "";
		}
		if (mem.signPos.isEmpty() || !(level.getBlockState(mem.signPos.get()).getBlock() instanceof DorfschildBlock)) {
			mem.signPos = placeSign(level, door);
			mem.signText = "";
		}
		if (mem.signPos.isEmpty() || !(level.getBlockState(mem.signPos.get()).getBlock() instanceof DorfschildBlock)) {
			return;
		}
		BlockPos signPos = mem.signPos.get();
		int icon = residents.size() > 1 ? 12 : iconFor(v.getVillagerData().profession());
		int stars = 0;
		for (Villager r : residents) {
			stars = Math.max(stars, Math.min(5, r.getVillagerData().level()));
		}
		String joined = icon + ":" + residents.size() + ":" + stars;
		if (joined.equals(mem.signText)) {
			return;
		}
		BlockState cur = level.getBlockState(signPos);
		level.setBlock(signPos, cur.setValue(DorfschildBlock.ICON, icon).setValue(DorfschildBlock.LEVEL, stars), 3);
		mem.signText = joined;
		// Mitbewohner sollen das Schild nicht ueberschreiben: gleiche Position merken.
		for (Villager r : residents) {
			VillagerMemory rm = r.getAttachedOrCreate(VillagerPlus.MEMORY);
			rm.signPos = mem.signPos;
		}
	}

	/** Symbol-Nummer (siehe Texturen dorfschild_N) fuer einen Beruf: Vanilla direkt, fremde Berufe ueber ihre Taetigkeit. */
	private static int iconFor(net.minecraft.core.Holder<net.minecraft.world.entity.npc.villager.VillagerProfession> prof) {
		String job = de.villagerplus.Compat.jobKey(prof);
		String path = prof.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
		if (job == null) {
			job = switch (path) {
				case "farmer" -> "farmer";
				case "shepherd" -> "shepherd";
				case "butcher" -> "butcher";
				case "fisherman" -> "fisherman";
				case "armorer", "toolsmith", "weaponsmith" -> "smith";
				case "cleric" -> "cleric";
				case "mason" -> "mason";
				case "fletcher" -> "fletcher";
				case "leatherworker" -> "leatherworker";
				case "cartographer" -> "cartographer";
				case "librarian" -> "librarian";
				default -> "";
			};
		}
		return switch (job) {
			case "farmer" -> 1;
			case "shepherd" -> 2;
			case "butcher" -> 3;
			case "fisherman" -> 4;
			case "smith" -> 5;
			case "cleric" -> 6;
			case "mason" -> 7;
			case "fletcher" -> 8;
			case "leatherworker" -> 9;
			case "cartographer" -> 10;
			case "librarian" -> 11;
			default -> path.equals("nitwit") ? 13 : 0;
		};
	}

	private static String trim(String s) {
		return s.length() > 15 ? s.substring(0, 15) : s;
	}

	/** Setzt ein Wandschild neben die Tuer an die Hausaussenwand. */
	private static Optional<BlockPos> placeSign(ServerLevel level, BlockPos door) {
		BlockState doorState = level.getBlockState(door);
		Direction facing = doorState.getValue(DoorBlock.FACING);
		BlockPos upper = door.above();
		BlockPos best = null;
		Direction bestOut = null;
		for (Direction out : new Direction[] {facing, facing.getOpposite()}) {
			for (Direction side : new Direction[] {out.getClockWise(), out.getCounterClockWise()}) {
				BlockPos wall = upper.relative(side);
				BlockPos spot = wall.relative(out);
				BlockState ws = level.getBlockState(wall);
				if (ws.isFaceSturdy(level, wall, out) && level.getBlockState(spot).isAir()) {
					boolean sky = level.canSeeSky(spot);
					if (best == null || sky) {
						best = spot;
						bestOut = out;
						if (sky) {
							break;
						}
					}
				}
			}
		}
		if (best == null) {
			return Optional.empty();
		}
		level.setBlock(best, ModBlocks.DORFSCHILD.defaultBlockState().setValue(DorfschildBlock.FACING, bestOut), 3);
		return Optional.of(best);
	}

	/** Schild in der Holzart der Tuer ("spruce_door" -> "spruce_wall_sign"), sonst Eiche. */
	private static Block signFor(Block door) {
		Identifier id = BuiltInRegistries.BLOCK.getKey(door);
		String path = id.getPath();
		if (path.endsWith("_door")) {
			Identifier signId = Identifier.fromNamespaceAndPath(id.getNamespace(), path.substring(0, path.length() - 5) + "_wall_sign");
			Block candidate = BuiltInRegistries.BLOCK.getOptional(signId).orElse(Blocks.OAK_WALL_SIGN);
			return candidate instanceof WallSignBlock ? candidate : Blocks.OAK_WALL_SIGN;
		}
		return Blocks.OAK_WALL_SIGN;
	}

	/** Zufaelliger Name, der unter den Dorfbewohnern in der Naehe noch nicht vergeben ist (nach 12 Versuchen mit Nummer). */
	private static String uniqueName(Villager v) {
		java.util.Set<String> used = new java.util.HashSet<>();
		for (Villager o : v.level().getEntitiesOfClass(Villager.class, v.getBoundingBox().inflate(96), x -> x != v)) {
			used.add(o.getAttachedOrCreate(VillagerPlus.MEMORY).name);
			if (o.hasCustomName()) {
				used.add(o.getCustomName().getString());
			}
		}
		for (int i = 0; i < 12; i++) {
			String n = Names.random(v.getRandom());
			if (!used.contains(n)) {
				return n;
			}
		}
		String base = Names.random(v.getRandom());
		int k = 2;
		while (used.contains(base + " " + k)) {
			k++;
		}
		return base + " " + k;
	}}
