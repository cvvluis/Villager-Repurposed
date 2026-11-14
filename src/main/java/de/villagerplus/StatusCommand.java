package de.villagerplus;

import com.mojang.brigadier.CommandDispatcher;

import com.mojang.brigadier.arguments.StringArgumentType;

import de.villagerplus.job.JobManager;

import de.villagerplus.job.Mining;

import de.villagerplus.village.Board;

import de.villagerplus.village.Names;

import de.villagerplus.village.VillageData;

import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Villages;
import de.villagerplus.village.VillageStats;

import de.villagerplus.village.Wellbeing;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandSourceStack;

import net.minecraft.commands.Commands;

import net.minecraft.core.BlockPos;

import net.minecraft.network.chat.Component;

import net.minecraft.server.level.ServerLevel;

import net.minecraft.world.entity.npc.villager.Villager;

import net.minecraft.world.level.block.entity.BellBlockEntity;

import net.minecraft.world.phys.AABB;

/**

 * {@code /villagerplus tafel [uebersicht|details|expeditionen]}: die Dorftafel auch per Befehl (und fuer die anklickbaren Reiter);

 * {@code /villagerplus status}: Ueberblick ueber Dorfbewohner und Dorf in der Naehe (hilfreich zum Testen).

 */

public final class StatusCommand {

	private StatusCommand() {

	}

	public static void register() {

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));

	}

	private static int tafel(CommandSourceStack src, String tab) {

		ServerLevel level = src.getLevel();

		BlockPos at = BlockPos.containing(src.getPosition());

		String shown = tab.equals("details") || tab.equals("expeditionen") ? tab : "uebersicht";
		VillageRef ref = Villages.villageNear(level, at, 160);
		if (ref == null) return 0;
		Board.show(line -> src.sendSuccess(() -> line, false), level, ref, shown);
		return 1;

	}

	/** Nur in der Entwicklungsumgebung: baut einen Entwurf des Architekten sofort vor die Fuesse (zum Betrachten/Testen). */

	private static int bauprobe(CommandSourceStack src, String kindName, int seed, String biome) {

		ServerLevel level = src.getLevel();

		BlockPos at = BlockPos.containing(src.getPosition());

		BlockPos bell = null;

		for (BlockPos p : BlockPos.betweenClosed(at.offset(-90, -30, -90), at.offset(90, 30, 90))) {

			if (level.getBlockEntity(p) instanceof BellBlockEntity b) {

				bell = p.immutable();

				break;

			}

		}

		if (bell == null) {

			return 0;

		}

		String k = kindName.toLowerCase(java.util.Locale.ROOT);

		Villager hint = level.getEntitiesOfClass(Villager.class, new AABB(bell).inflate(60)).stream().findFirst().orElse(null);

		de.villagerplus.build.Palette palette = de.villagerplus.build.Palette.detect(level, bell, hint, seed);

		if (!biome.isEmpty()) {

			palette = de.villagerplus.build.Palette.curate(palette, biome, seed);

		}

		net.minecraft.core.Direction dir = net.minecraft.core.Direction.fromYRot(src.getRotation().y);

		BlockPos c = at.relative(dir, 11);

		boolean architect = k.equals("house") || k.equals("school") || k.equals("storage");

		de.villagerplus.build.Architect.Design design = null;

		String type = k.startsWith("decor") ? de.villagerplus.build.Blueprints.DECOR : k;

		int half;

		if (architect) {

			design = de.villagerplus.build.Architect.design(de.villagerplus.build.Architect.Kind.valueOf(k.toUpperCase(java.util.Locale.ROOT)), ((long) seed << 6) | de.villagerplus.build.Palette.profileCode(level, bell));

			half = design.half();

		} else if (k.equals("farm")) {

			half = 4;

		} else {

			half = de.villagerplus.build.Landmarks.half(type);

		}

		half += 6;

		for (int dx = -half; dx <= half; dx++) {

			for (int dz = -half; dz <= half; dz++) {

				for (int dy = -4; dy < 16; dy++) {

					level.setBlock(c.offset(dx, dy, dz), dy < -1 ? net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState()

						: dy == -1 ? net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState()

						: net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);

				}

			}

		}

		java.util.List<de.villagerplus.build.Step> steps;

		if (architect) {

			steps = de.villagerplus.build.Architect.build(design, palette, c, dir.getOpposite());

		} else if (k.equals("farm")) {

			steps = de.villagerplus.build.Blueprints.farm(c, 0 + 10 * 4);

		} else {

			long sd = type.equals(de.villagerplus.build.Blueprints.DECOR) ? de.villagerplus.build.Landmarks.decorSeed(Integer.parseInt(k.substring(5)), seed) : seed;

			steps = de.villagerplus.build.Landmarks.build(type, sd, palette, c, dir.getOpposite());

		}

		for (de.villagerplus.build.Step st : steps) {

			net.minecraft.world.level.block.Block b;

			if (st.linkedTo() != null) {

				b = level.getBlockState(st.linkedTo()).getBlock();

			} else if (st.item() != null) {

				b = net.minecraft.world.level.block.Block.byItem(st.item());

			} else if (st.tag() != null) {

				b = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse("minecraft:red_bed"));

			} else {

				b = net.minecraft.world.level.block.Blocks.AIR;

			}

			level.setBlock(st.pos(), st.make().apply(b), 3);

		}

		if (k.equals("storage") || k.equals("school")) {

			net.minecraft.world.level.levelgen.structure.BoundingBox bb = de.villagerplus.build.Furnisher.boxOf(steps);

			if (k.equals("storage")) {

				de.villagerplus.build.Furnisher.placeDoubleChests(level, bb, 8);

			} else {

				// Previewing a generated school is free of world-stock mutations; only a registered village school
				// is furnished through the paid production path when its construction completes.

			}

		}

		String kind = k;

		String info = kind + " seed " + seed + " [" + de.villagerplus.build.Palette.lastProfile + "] " + design + " bei " + c.toShortString() + " Palette " + palette;

		src.sendSuccess(() -> Component.literal(info), false);

		return 1;

	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {

		if (net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) {


            dispatcher.register(Commands.literal("vptest090").then(Commands.argument("mode",StringArgumentType.word()).executes(ctx -> {
                try { return de.villagerplus.dev.Checks090.run(ctx.getSource(),StringArgumentType.getString(ctx,"mode")); }
                catch(Exception ex) { ex.printStackTrace(); ctx.getSource().sendFailure(Component.literal(ex.toString())); return 0; }
            })));
			dispatcher.register(Commands.literal("vpkaravane").executes(ctx -> {

				var source = ctx.getSource();

				var ref = de.villagerplus.village.Villages.villageNear(source.getLevel(), BlockPos.containing(source.getPosition()), 96);

				boolean spawned = ref != null && de.villagerplus.village.Caravan.spawn(source.getLevel(), ref);

				source.sendSuccess(() -> Component.literal(spawned ? "Karawane gestartet" : "Kein Dorf/Platz oder Karawane bereits unterwegs"), false);

				return spawned ? 1 : 0;

			}));

			dispatcher.register(Commands.literal("vpboard").executes(ctx -> {
				var source = ctx.getSource();
				var ref = de.villagerplus.village.Villages.villageNear(source.getLevel(), BlockPos.containing(source.getPosition()), 96);
				if (ref == null || ref.data().extra.board.isEmpty() || source.getPlayer() == null) {
					source.sendFailure(Component.literal("Kein Dorf/keine Tafel in der Nähe"));
					return 0;
				}
				de.villagerplus.block.DorftafelBlock.open(source.getLevel(), source.getPlayer(), ref.data().extra.board.get());
				return 1;
			}));

			dispatcher.register(Commands.literal("vpdemotest").executes(ctx -> {
				// Nur Entwicklung: Haus bauen, als Dorfgebaeude registrieren und sofort den Abriss anordnen.
				var source = ctx.getSource();
				var level = source.getLevel();
				BlockPos at = BlockPos.containing(source.getPosition());
				var ref = de.villagerplus.village.Villages.villageNear(level, at, 96);
				if (ref == null || bauprobe(source, "house", 3, "") == 0) {
					source.sendFailure(Component.literal("Kein Dorf in der Nähe"));
					return 0;
				}
				net.minecraft.core.Direction dir = net.minecraft.core.Direction.fromYRot(source.getRotation().y);
				BlockPos origin = at.relative(dir, 11);
				var hint = level.getEntitiesOfClass(Villager.class, new AABB(ref.bell()).inflate(60)).stream().findFirst().orElse(null);
				var palette = de.villagerplus.build.Palette.detect(level, ref.bell(), hint, 3);
				long designSeed = ((long) 3 << 6) | de.villagerplus.build.Palette.profileCode(level, ref.bell());
				var list = new java.util.ArrayList<>(de.villagerplus.build.Builder.builtOf(ref));
				list.add(new de.villagerplus.build.Built("house", origin, dir.getOpposite().get2DDataValue(), (int) (designSeed << 2), palette.ids()));
				ref.bellEntity().setAttached(VillagerPlus.BUILDINGS, java.util.List.copyOf(list));
				String msg = de.villagerplus.village.Demolition.request(level, ref, origin);
				source.sendSuccess(() -> Component.literal("Testhaus bei " + origin.toShortString() + ": " + msg), false);
				return 1;
			}));

			dispatcher.register(Commands.literal("vpexpedition").executes(ctx -> {
				var source = ctx.getSource();
				var ref = de.villagerplus.village.Villages.villageNear(source.getLevel(), BlockPos.containing(source.getPosition()), 96);
				boolean started = ref != null && de.villagerplus.village.Expedition.debugStart(source.getLevel(), ref);
				source.sendSuccess(() -> Component.literal(started ? "Expedition gestartet" : "Kein Dorf, zu wenig Einwohner oder Expedition läuft schon"), false);
				return started ? 1 : 0;
			}));

			dispatcher.register(Commands.literal("vpplan").then(Commands.argument("type", StringArgumentType.word())

				.then(Commands.argument("seed", com.mojang.brigadier.arguments.IntegerArgumentType.integer()).executes(ctx -> {

					CommandSourceStack src = ctx.getSource();

					ServerLevel level = src.getLevel();

					BlockPos at = BlockPos.containing(src.getPosition());

					for (BlockPos p : BlockPos.betweenClosed(at.offset(-90, -30, -90), at.offset(90, 30, 90))) {

						if (level.getBlockEntity(p) instanceof BellBlockEntity b && b.getAttached(VillagerPlus.VILLAGE) != null) {

							VillageRef ref = new VillageRef(p.immutable(), b.getAttached(VillagerPlus.VILLAGE), b);

							Villager hint = level.getEntitiesOfClass(Villager.class, new AABB(p).inflate(60)).stream().findFirst().orElse(null);

							net.minecraft.core.Direction dir = net.minecraft.core.Direction.fromYRot(src.getRotation().y);

							boolean ok = de.villagerplus.build.Builder.debugPlan(level, ref, StringArgumentType.getString(ctx, "type"), at.relative(dir, 6), dir,

								com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "seed"), hint);

							src.sendSuccess(() -> Component.literal(ok ? "Bauauftrag angelegt" : "Auftrag nicht moeglich (laeuft schon einer?)"), false);

							return ok ? 1 : 0;

						}

					}

					return 0;

				}))));

			dispatcher.register(Commands.literal("vpwall").then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(10, 40))

				.executes(ctx -> {

					CommandSourceStack src = ctx.getSource();

					ServerLevel level = src.getLevel();

					BlockPos at = BlockPos.containing(src.getPosition());

					for (BlockPos p : BlockPos.betweenClosed(at.offset(-90, -30, -90), at.offset(90, 30, 90))) {

						if (level.getBlockEntity(p) instanceof BellBlockEntity b && b.getAttached(VillagerPlus.VILLAGE) != null) {

							VillageRef ref = new VillageRef(p.immutable(), b.getAttached(VillagerPlus.VILLAGE), b);

							Villager hint = level.getEntitiesOfClass(Villager.class, new AABB(p).inflate(60)).stream().findFirst().orElse(null);

							int n = de.villagerplus.build.Builder.debugWall(level, ref, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "radius"), hint);

							src.sendSuccess(() -> Component.literal("Mauer: " + n + " Bloecke"), false);

							return 1;

						}

					}

					return 0;

				})));

			dispatcher.register(Commands.literal("vpprobe").then(Commands.argument("kind", StringArgumentType.word())

				.then(Commands.argument("seed", com.mojang.brigadier.arguments.IntegerArgumentType.integer())

					.executes(ctx -> bauprobe(ctx.getSource(), StringArgumentType.getString(ctx, "kind"),

						com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "seed"), ""))

					.then(Commands.argument("biome", StringArgumentType.word()).executes(ctx -> bauprobe(ctx.getSource(),

						StringArgumentType.getString(ctx, "kind"), com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "seed"),

						StringArgumentType.getString(ctx, "biome")))))));

		}

		dispatcher.register(Commands.literal("villagerplus")

			.then(Commands.literal("tafel").executes(ctx -> tafel(ctx.getSource(), "uebersicht"))

				.then(Commands.argument("tab", StringArgumentType.word()).executes(ctx -> tafel(ctx.getSource(), StringArgumentType.getString(ctx, "tab")))))

			.then(Commands.literal("perf").executes(ctx -> {

				ctx.getSource().sendSuccess(() -> Component.literal(JobManager.perf()), false);

				return 1;

			}))

			.then(Commands.literal("status").executes(ctx -> {

				CommandSourceStack src = ctx.getSource();

				ServerLevel level = src.getLevel();

				AABB box = new AABB(src.getPosition(), src.getPosition()).inflate(160);

				int count = 0;

				for (Villager v : level.getEntitiesOfClass(Villager.class, box)) {

					VillagerMemory mem = v.getAttachedOrCreate(VillagerPlus.MEMORY);

					Wellbeing.Status s = Wellbeing.of(level, v, mem);

					String line = (mem.name.isEmpty() ? "?" : mem.name) + " (" + Names.profession(v.getVillagerData().profession()) + "): "

						+ JobManager.activityOf(v, level) + " | Stimmung " + s.mood() + "% Sättigung " + s.satiety() + "% Energie "

						+ s.energy() + "% Soziales " + s.social() + "% | Haus " + (s.home() ? "ja" : "nein") + ", Kiste "

						+ (mem.homeChest.isPresent() ? "ja" : "nein") + " | Arbeiten " + mem.jobsDone + ", Gespräche " + mem.chats;

					src.sendSuccess(() -> Component.literal(line), false);

					count++;

				}

				final int total = count;

				src.sendSuccess(() -> Component.literal("Dorfbewohner in der Nähe: " + total), false);

				for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(src.getPosition()).offset(-80, -20, -80),

					BlockPos.containing(src.getPosition()).offset(80, 20, 80))) {

					if (level.getBlockEntity(p) instanceof BellBlockEntity bell && bell.getAttached(VillagerPlus.VILLAGE) != null) {

						VillageData d = bell.getAttached(VillagerPlus.VILLAGE);
						VillageRef ref = new VillageRef(p.immutable(), d, bell);

						String info = "Dorf an Glocke " + p.toShortString() + " [" + de.villagerplus.village.VillageAdmin.summary(ref) + "]: Kiste " + d.chest.map(BlockPos::toShortString).orElse("-")

							+ ", Häuser gebaut " + d.houses + ", Felder " + d.farms + ", Brunnen " + (d.wellBuilt ? "ja" : "nein") + ", Rathaus " + (d.townHallBuilt ? "ja" : "nein") + ", Mauer " + (d.wallBuilt ? "ja" : "nein")

							+ ", Gehege " + d.extra.pen.map(BlockPos::toShortString).orElse("-") + ", Schule " + d.extra.school.map(BlockPos::toShortString).orElse("-")
							+ (d.extra.school.isPresent() ? " [" + de.villagerplus.job.School.furnishingStatus(level, d.extra.school.get()) + "]" : "")

							+ ", Lager " + d.extra.lager.map(BlockPos::toShortString).orElse("-") + "+" + d.extra.lagerMore.size()

							+ ", nächste Karawane bei Spielzeit " + d.extra.nextCaravan

							+ ", Auftrag " + (d.hasPlan() ? d.planType + " bei " + d.planOrigin.get().toShortString() + " Schritt " + d.planStep : "keiner")

							+ ", Reichtum-Aufbauten " + d.extra.decor + ", Mine " + Mining.describe(d)
							+ ", Baumaterial " + VillageStats.materialSummary(level, ref);

						src.sendSuccess(() -> Component.literal(info), false);

					}

				}

				return count;

			})));

	}

}

