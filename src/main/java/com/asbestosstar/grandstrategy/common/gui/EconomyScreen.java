package com.asbestosstar.grandstrategy.common.gui;

import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.VillagerJob;
import com.asbestosstar.grandstrategy.common.world.PhysicalVillagerSystem;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Population, resource production and live physical-worker task management. */
public final class EconomyScreen extends StrategyScreen {
	private final String civilisationId;
	private int workerPage;
	private String statusMessage = "";

	public EconomyScreen(Screen parent, Civilisation civilisation) {
		this(parent, civilisation == null ? null : civilisation.getId(), 0);
	}

	public EconomyScreen(Screen parent, String civilisationId) {
		this(parent, civilisationId, 0);
	}

	private EconomyScreen(Screen parent, String civilisationId, int workerPage) {
		super("Economy & Population", parent);
		this.civilisationId = civilisationId;
		this.workerPage = Math.max(0, workerPage);
	}

	@Override
	protected void init() {
		beginIconLayout();
		StrategyClientContext.requestSync();
		addIconButton(UiIcon.BACK, "Back to map", 6, 6, button -> this.minecraft.setScreen(getParentScreen()));

		Civilisation civilisation = getCivilisation();
		if (civilisation == null)
			return;

		this.addRenderableWidget(Button
				.builder(Component.literal("Conscription: " + civilisation.getConscriptionLevel().getDisplayName()),
						button -> {
							boolean queued = StrategyClientContext.requestCycleConscription();
							if (!queued)
								statusMessage = "Not connected to a Grand Strategy server.";
							else
								scheduleRefresh();
						})
				.bounds(34, 6, 190, 20).build());

		this.addRenderableWidget(Button.builder(Component.literal("Auto assign villagers"), button -> {
			boolean queued = StrategyClientContext.requestAutoAssign();
			if (!queued)
				statusMessage = "Not connected to a Grand Strategy server.";
			else
				scheduleRefresh();
		}).bounds(230, 6, 150, 20).build());

		int rowY = 92;
		for (VillagerJob job : VillagerJob.values()) {
			if (job != VillagerJob.SOLDIER) {
				final VillagerJob selectedJob = job;
				addIconButton(UiIcon.ZOOM_OUT, "Remove one from " + selectedJob.getDisplayName(), this.width - 52,
						rowY - 4, 20, 18, button -> {
							boolean queued = StrategyClientContext.requestReassignFrom(selectedJob);
							if (!queued)
								statusMessage = "Not connected to a Grand Strategy server.";
							else
								scheduleRefresh();
						});
				addIconButton(UiIcon.ZOOM_IN, "Assign one to " + selectedJob.getDisplayName(), this.width - 28,
						rowY - 4, 20, 18, button -> {
							boolean queued = StrategyClientContext.requestReassignTo(selectedJob);
							if (!queued)
								statusMessage = "Not connected to a Grand Strategy server.";
							else
								scheduleRefresh();
						});
			}
			rowY += 20;
		}

		int pagerY = this.height - 27;
		this.addRenderableWidget(Button.builder(Component.literal("< Prev"), button -> {
			if (workerPage > 0)
				workerPage--;
		}).bounds(this.width - 132, pagerY, 58, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Next >"), button -> {
			int pages = workerPageCount();
			if (workerPage + 1 < pages)
				workerPage++;
		}).bounds(this.width - 70, pagerY, 62, 20).build());
	}

	@Override
	protected void renderCustom(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		Civilisation civilisation = getCivilisation();
		graphics.text(this.font, "Economy & Population", 18, 38, TEXT, true);

		if (civilisation == null) {
			graphics.text(this.font, "Create a country from the map before assigning villagers.", 18, 64, WARNING_TEXT,
					true);
			return;
		}

		graphics.text(this.font,
				civilisation.getName() + " | Population: " + civilisation.getPopulation() + " | Assigned: "
						+ civilisation.totalAssignedVillagers() + " | Soldiers: "
						+ civilisation.getJobCount(VillagerJob.SOLDIER),
				18, 56, TEXT, true);

		graphics.text(this.font,
				"Material resources are physical: use the supply-depot chests. Worker activity below is live server state.",
				18, 70, 0xFFE1E8EF, true);

		int rowY = 92;
		for (VillagerJob job : VillagerJob.values()) {
			int count = civilisation.getJobCount(job);
			int colour = job == VillagerJob.SOLDIER ? 0xFFFFC857 : TEXT;
			String note = job == VillagerJob.SOLDIER ? " (set by conscription)" : "";
			graphics.text(this.font, job.getDisplayName() + ": " + count + note, 24, rowY, colour, true);
			rowY += 20;
		}

		int infoY = rowY + 2;
		graphics.text(this.font, "Factories: " + civilisation.getFactories() + "  progress "
				+ percent(civilisation.getFactoryConstructionProgress()) + "   |   Roads: "
				+ civilisation.getRoadSegments() + "  progress " + percent(civilisation.getRoadConstructionProgress()),
				18, infoY, MUTED_TEXT, true);
		graphics.text(this.font,
				"Political Power: " + format(civilisation.getPoliticalPower()) + "   |   Research: "
						+ format(civilisation.getResearchPoints()) + "   |   Birth progress: "
						+ percent(civilisation.getPopulationGrowthAccumulator() * 100.0),
				18, infoY + 13, MUTED_TEXT, true);

		renderWorkerTable(graphics, infoY + 31);

		if (!statusMessage.isBlank()) {
			graphics.text(this.font, statusMessage, 18, this.height - 18, BAD_TEXT, true);
		}
	}

	private void renderWorkerTable(GuiGraphicsExtractor graphics, int tableY) {
		List<PhysicalVillagerSystem.VillagerMapMarker> workers = currentWorkers();
		int rowsPerPage = workerRowsPerPage(tableY);
		int pages = Math.max(1, (workers.size() + rowsPerPage - 1) / rowsPerPage);
		if (workerPage >= pages)
			workerPage = pages - 1;

		int tableX = 18;
		int tableRight = this.width - 8;
		int tableWidth = Math.max(220, tableRight - tableX);
		int headerH = 15;
		int rowH = 14;

		graphics.fill(tableX, tableY, tableRight, tableY + headerH, 0xFF202A33);
		graphics.outline(tableX, tableY, tableWidth, headerH, 0xFF71808D);

		int personX = tableX + 5;
		int roleX = tableX + 70;
		int toolX = tableX + 178;
		int statusX = tableX + 235;
		int positionX = Math.max(statusX + 150, tableRight - 105);

		graphics.text(this.font, "Person", personX, tableY + 4, 0xFFE8EEF4, true);
		graphics.text(this.font, "Role", roleX, tableY + 4, 0xFFE8EEF4, true);
		graphics.text(this.font, "Tool", toolX, tableY + 4, 0xFFE8EEF4, true);
		graphics.text(this.font, "Current status", statusX, tableY + 4, 0xFFE8EEF4, true);
		if (positionX < tableRight - 20) {
			graphics.text(this.font, "Position", positionX, tableY + 4, 0xFFE8EEF4, true);
		}

		int start = workerPage * rowsPerPage;
		int end = Math.min(workers.size(), start + rowsPerPage);
		for (int index = start; index < end; index++) {
			PhysicalVillagerSystem.VillagerMapMarker worker = workers.get(index);
			int localRow = index - start;
			int y = tableY + headerH + localRow * rowH;
			int fill = (localRow & 1) == 0 ? 0x78141B21 : 0x78303A42;
			graphics.fill(tableX, y, tableRight, y + rowH, fill);
			graphics.outline(tableX, y, tableWidth, rowH, 0x553F4B55);

			String person = "Person " + (worker.assignmentIndex() + 1);
			String role = displayJob(worker.job());
			String tool = displayTool(worker.toolTier());
			String status = worker.status() == null || worker.status().isBlank() ? "Waiting" : worker.status();
			if (worker.carriedItems() > 0 && !status.contains("(" + worker.carriedItems())) {
				status += " | carrying " + worker.carriedItems();
			}

			graphics.text(this.font, fitText(person, 60), personX, y + 3, TEXT, true);
			graphics.text(this.font, fitText(role, 103), roleX, y + 3,
					"SOLDIER".equals(worker.job()) ? WARNING_TEXT : TEXT, true);
			graphics.text(this.font, fitText(tool, 52), toolX, y + 3, toolColour(worker.toolTier()), true);

			int statusWidth = Math.max(80, positionX - statusX - 6);
			graphics.text(this.font, fitText(status, statusWidth), statusX, y + 3, statusColour(status), true);

			if (positionX < tableRight - 20) {
				String position = worker.blockX() + "," + worker.blockY() + "," + worker.blockZ();
				graphics.text(this.font, fitText(position, tableRight - positionX - 4), positionX, y + 3, MUTED_TEXT,
						true);
			}
		}

		int footerY = tableY + headerH + rowsPerPage * rowH + 3;
		graphics.text(this.font,
				workers.size() + " physical people | page " + (workerPage + 1) + "/" + pages
						+ " | status updates from the authoritative worker brain",
				tableX, Math.min(footerY, this.height - 18), MUTED_TEXT, true);
	}

	private List<PhysicalVillagerSystem.VillagerMapMarker> currentWorkers() {
		if (civilisationId == null)
			return List.of();
		return StrategyClientContext.villagers().stream()
				.filter(worker -> worker != null && civilisationId.equals(worker.civilisationId()))
				.sorted(Comparator.comparingInt(PhysicalVillagerSystem.VillagerMapMarker::assignmentIndex)
						.thenComparing(worker -> worker.uuid() == null ? "" : worker.uuid()))
				.toList();
	}

	private int workerRowsPerPage(int tableY) {
		int available = Math.max(70, this.height - tableY - 45);
		return Math.max(4, available / 14);
	}

	private int workerPageCount() {
		int tableY = 92 + VillagerJob.values().length * 20 + 2 + 31;
		int rows = workerRowsPerPage(tableY);
		int count = currentWorkers().size();
		return Math.max(1, (count + rows - 1) / rows);
	}

	private String fitText(String text, int width) {
		if (text == null)
			return "";
		if (width <= 8 || this.font.width(text) <= width)
			return text;
		String ellipsis = "...";
		int target = Math.max(0, width - this.font.width(ellipsis));
		int end = text.length();
		while (end > 0 && this.font.width(text.substring(0, end)) > target)
			end--;
		return text.substring(0, end) + ellipsis;
	}

	private static String displayJob(String token) {
		if (token == null || token.isBlank())
			return "Unassigned";
		try {
			return VillagerJob.valueOf(token).getDisplayName();
		} catch (IllegalArgumentException ignored) {
			return readable(token);
		}
	}

	private static String displayTool(String token) {
		if (token == null || token.isBlank())
			return "Hand";
		return readable(token);
	}

	private static int toolColour(String token) {
		if (token == null)
			return MUTED_TEXT;
		return switch (token) {
		case "DIAMOND" -> 0xFF85E8E8;
		case "IRON" -> 0xFFE7E7E7;
		case "STONE" -> 0xFFC1C1C1;
		case "WOOD" -> 0xFFD5AD75;
		default -> MUTED_TEXT;
		};
	}

	private static int statusColour(String status) {
		if (status == null)
			return TEXT;
		String lower = status.toLowerCase(Locale.ROOT);
		if (lower.contains("waiting") || lower.contains("hungry") || lower.contains("replanning"))
			return WARNING_TEXT;
		if (lower.contains("escaping") || lower.contains("wrong profession"))
			return BAD_TEXT;
		if (lower.contains("mining") || lower.contains("farming") || lower.contains("building")
				|| lower.contains("researching") || lower.contains("operating"))
			return GOOD_TEXT;
		return TEXT;
	}

	private static String readable(String token) {
		String lower = token.toLowerCase(Locale.ROOT).replace('_', ' ');
		if (lower.isBlank())
			return "";
		return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
	}

	private Civilisation getCivilisation() {
		return civilisationId == null ? null : StrategyClientContext.getCivilisation(civilisationId);
	}

	private static String format(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}

	private static String percent(double value) {
		return String.format(Locale.ROOT, "%.1f%%", Math.max(0.0, Math.min(100.0, value)));
	}

	@Override
	protected StrategyScreen recreate() {
		return new EconomyScreen(getParentScreen(), civilisationId, workerPage);
	}
}
