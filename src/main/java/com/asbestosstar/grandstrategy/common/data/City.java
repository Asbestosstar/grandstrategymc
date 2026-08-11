package com.asbestosstar.grandstrategy.common.data;

/**
 * Strategy-level city attached to exactly one providence.
 *
 * Every city owns one physical beacon command post in the Minecraft world. The
 * city/command-post controller is deliberately separate from the chunk-by-chunk
 * territorial claims inside the surrounding providence.
 */
public class City {
	private String id;
	private String name;
	private int blockX;
	private int blockZ;
	private String controllerId;
	private boolean nationalCapital;
	private boolean supplyCapital;
	private int commandPostY = Integer.MIN_VALUE;

	public City() {
	}

	public City(String id, String name, int blockX, int blockZ, String controllerId, boolean nationalCapital,
			boolean supplyCapital) {
		this.id = id;
		this.name = name;
		this.blockX = blockX;
		this.blockZ = blockZ;
		this.controllerId = controllerId;
		this.nationalCapital = nationalCapital;
		this.supplyCapital = supplyCapital;
	}

	public String getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public int getBlockX() {
		return blockX;
	}

	public int getBlockZ() {
		return blockZ;
	}

	public String getControllerId() {
		return controllerId;
	}

	public boolean isNationalCapital() {
		return nationalCapital;
	}

	public boolean isSupplyCapital() {
		return supplyCapital;
	}

	public int getCommandPostY() {
		return commandPostY;
	}

	public boolean hasCommandPostPosition() {
		return commandPostY != Integer.MIN_VALUE;
	}

	public void setName(String name) {
		if (name != null && !name.isBlank())
			this.name = name;
	}

	public void setPosition(int blockX, int blockZ) {
		this.blockX = blockX;
		this.blockZ = blockZ;
		// Moving a city invalidates the old physical command-post height.
		this.commandPostY = Integer.MIN_VALUE;
	}

	public void setCommandPostY(int commandPostY) {
		this.commandPostY = commandPostY;
	}

	public void clearCommandPostPosition() {
		this.commandPostY = Integer.MIN_VALUE;
	}

	public void setControllerId(String controllerId) {
		this.controllerId = controllerId;
	}

	public void setNationalCapital(boolean nationalCapital) {
		this.nationalCapital = nationalCapital;
	}

	public void setSupplyCapital(boolean supplyCapital) {
		this.supplyCapital = supplyCapital;
	}
}
