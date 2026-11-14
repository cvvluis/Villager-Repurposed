package de.villagerplus.client;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;

/** Immutable-per-frame inputs for the village-name text drawn on a Dorftafel. */
public final class DorftafelNameRenderState extends BlockEntityRenderState {
	private String villageName = "";
	private Direction facing = Direction.NORTH;
	private boolean wide;
	private int[] relief = new int[0];

	public int[] relief() {
		return relief;
	}

	public void setRelief(int[] relief) {
		this.relief = relief;
	}

	public String villageName() {
		return villageName;
	}

	public void setVillageName(String villageName) {
		this.villageName = villageName;
	}

	public Direction facing() {
		return facing;
	}

	public void setFacing(Direction facing) {
		this.facing = facing;
	}

	/** True on the large four-block board. */
	public boolean wide() {
		return wide;
	}

	public void setWide(boolean wide) {
		this.wide = wide;
	}
}
