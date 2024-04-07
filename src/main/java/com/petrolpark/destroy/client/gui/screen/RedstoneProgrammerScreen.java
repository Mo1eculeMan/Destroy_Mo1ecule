package com.petrolpark.destroy.client.gui.screen;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.vertex.PoseStack;
import com.petrolpark.destroy.client.gui.DestroyGuiTextures;
import com.petrolpark.destroy.client.gui.DestroyIcons;
import com.petrolpark.destroy.client.gui.menu.RedstoneProgrammerMenu;
import com.petrolpark.destroy.config.DestroyAllConfigs;
import com.petrolpark.destroy.network.DestroyMessages;
import com.petrolpark.destroy.network.packet.RedstoneProgramSyncC2SPacket;
import com.petrolpark.destroy.util.DestroyLang;
import com.petrolpark.destroy.util.GuiHelper;
import com.petrolpark.destroy.util.RedstoneProgram;
import com.petrolpark.destroy.util.RedstoneProgram.Channel;
import com.petrolpark.destroy.util.RedstoneProgram.PlayMode;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.UIRenderHelper;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.widget.ScrollInput;
import com.simibubi.create.foundation.utility.animation.LerpedFloat;
import com.simibubi.create.foundation.utility.animation.LerpedFloat.Chaser;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

public class RedstoneProgrammerScreen extends AbstractSimiContainerScreen<RedstoneProgrammerMenu> {

    protected final RedstoneProgrammerMenu menu;
    protected final RedstoneProgram program;

    private DestroyGuiTextures background;
    private int width;

    // Areas
    public static final Rect2i ITEM_AREA = new Rect2i(3, 31, 73, 154);
    public static final Rect2i NOTE_AREA = new Rect2i(77, 31, 168, 154);

    // Spacing
    public static final int distanceBetweenChannels = 20;
    private static final int noteWidth = 4;

    // Scroll values
    private int verticalScroll;
    private LerpedFloat horizontalScroll = LerpedFloat.linear().startWithValue(0d);
    private LerpedFloat playhead = LerpedFloat.linear().startWithValue(0d);
    private boolean followPlayHead = false;

    // Mouse dragging information
    private boolean dragging;
    private int draggingChannel; // Which channel is being clicked
    private boolean draggingDeleting; // Whether clicking is deleting NOTE_AREA

    // Buttons
    private IconButton playPauseButton;
    private IconButton restartButton;
    private IconButton confirmButton;
    private Map<PlayMode, IconButton> modeButtons;
    private IconButton followPlayheadButton;

    // Scroll inputs
    private ScrollInput ticksPerBeatScroller;
    private ScrollInput beatsPerLineScroller;
    private ScrollInput linesPerBarScroller;

    // Syncing
    private boolean shouldSend;

    
    public RedstoneProgrammerScreen(RedstoneProgrammerMenu container, Inventory inv, Component title) {
        super(container, inv, title);
        menu = container;
        program = container.contentHolder;

        background = DestroyGuiTextures.REDSTONE_PROGRAMMER;
        modeButtons = new HashMap<>(PlayMode.values().length);
        playhead.setValue((float)noteWidth * (float)program.getAbsolutePlaytime());
    };

    @Override
    protected void init() {
        width = background.width;
        setWindowSize(width, background.height);
        super.init();
        clearWidgets();

        playPauseButton = new IconButton(leftPos + 7, topPos + 6, AllIcons.I_PLAY)
            .withCallback(() -> {
                program.paused = !program.paused;
                if (program.mode.powerRequired || program.mode == PlayMode.LOOP) program.mode = PlayMode.MANUAL;
                setPlayPauseButtonIcon();
                shouldSend = true;
            });
        setPlayPauseButtonIcon();
        addRenderableWidget(playPauseButton);

        restartButton = new IconButton(leftPos + 29, topPos + 6, AllIcons.I_REFRESH)
            .withCallback(() -> {
                program.restart();
                playhead.setValue(0f);
                shouldSend = true;
            });
        addRenderableWidgets(restartButton);

        confirmButton = new IconButton(leftPos + width - 33, topPos + background.height - 24, AllIcons.I_CONFIRM);
		confirmButton.withCallback(() -> {
            if (minecraft != null && minecraft.player != null) minecraft.player.closeContainer(); // It thinks minecraft and player might be null
        }); 
		addRenderableWidget(confirmButton);

        modeButtons.clear();
        for (PlayMode mode : PlayMode.values()) {
            IconButton button = new IconButton(leftPos + 27 + mode.ordinal() * 18, topPos + background.height - 24, DestroyIcons.get(mode));
            button.setToolTip(mode.description);
            button.withCallback(() -> {
                shouldSend = program.mode != mode;
                program.mode = mode;
                if (shouldSend && mode == PlayMode.LOOP) program.paused = false;
            });
            button.active = program.mode != mode;
            modeButtons.put(mode, button);
            addRenderableWidget(button);
        };

        ticksPerBeatScroller = new ScrollInput(leftPos + 6, topPos + background.height - 24, 18, 9)
            .setState(program.getTicksPerBeat())
            .calling(i -> {
                program.setTicksPerBeat(i);
                shouldSend = true;
            })
            .titled(DestroyLang.translate("tooltip.redstone_programmer.ticks_per_beat").component())
            .addHint(DestroyLang.translate("tooltip.redstone_programmer.ticks_per_beat.hint").component())
            .withRange(DestroyAllConfigs.SERVER.contraptions.minTicksPerBeat.get(), 81);
        ticksPerBeatScroller.setState(program.getTicksPerBeat());
        addRenderableWidget(ticksPerBeatScroller);

        beatsPerLineScroller = new ScrollInput(leftPos + 156, topPos + background.height - 24, 18, 9)
            .setState(program.beatsPerLine)
            .calling(i -> {
                program.beatsPerLine = i;
                shouldSend = true;
            })
            .titled(DestroyLang.translate("tooltip.redstone_programmer.beats_per_line").component())
            .addHint(DestroyLang.translate("tooltip.redstone_programmer.beats_per_line.hint").component())
            .withRange(0, 32);
        beatsPerLineScroller.setState(program.beatsPerLine);
        addRenderableWidget(beatsPerLineScroller);

        linesPerBarScroller = new ScrollInput(leftPos + 176, topPos + background.height - 24, 18, 9)
            .setState(program.linesPerBar)
            .calling(i -> {
                program.linesPerBar = i;
                shouldSend = true;
            })
            .titled(DestroyLang.translate("tooltip.redstone_programmer.lines_per_bar").component())
            .addHint(DestroyLang.translate("tooltip.redstone_programmer.lines_per_bar.hint").component())
            .withRange(0, 32);
        linesPerBarScroller.setState(program.linesPerBar);
        addRenderableWidget(linesPerBarScroller);

        followPlayheadButton = new IconButton(leftPos + 196, topPos + background.height - 24, AllIcons.I_CONFIG_OPEN)
            .withCallback(() -> followPlayHead = true);
        followPlayheadButton.setToolTip(DestroyLang.translate("tooltip.redstone_programmer.follow_playhead").component());
        addRenderableWidget(followPlayheadButton);
    };

    @Override
    public void containerTick() {
        super.containerTick();

        // Tick chasers
        playhead.chase((float)noteWidth * (float)program.getAbsolutePlaytime(), (float)noteWidth / program.getTicksPerBeat(), Chaser.LINEAR);
        if (playhead.getChaseTarget() == 0f) playhead.setValue(playhead.getChaseTarget()); //TODO fix
        horizontalScroll.tickChaser();
        playhead.tickChaser();

        // Set the mode of play
        for (Entry<PlayMode, IconButton> entry : modeButtons.entrySet()) {
            entry.getValue().active = program.mode != entry.getKey();
        };

        // Advance playhead
        program.tick();

        // Update play button icon to reflect whether the program is playing
        setPlayPauseButtonIcon();
        
        // Sync to server
        if (shouldSend) {
            DestroyMessages.sendToServer(new RedstoneProgramSyncC2SPacket(program));
            shouldSend = false;
        };
    };

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {

        int mX = (int)mouseX - getGuiLeft();
        int mY = (int)mouseY - getGuiTop();
        boolean inNoteArea = NOTE_AREA.contains(mX, mY);
        boolean inItemArea = ITEM_AREA.contains(mX, mY);

        // Adding/deleting Notes and Channels
        if (inItemArea || inNoteArea) {
            ImmutableList<Channel> channels = program.getChannels();
            double posInList = mouseY - topPos - NOTE_AREA.getY() + verticalScroll;
            int channelNo = (int)(posInList / distanceBetweenChannels);
            if (channelNo < 0 || channelNo >= channels.size()) return super.mouseClicked(mouseX, mouseY, button);
            Channel channel = channels.get(channelNo);

            if (inNoteArea) {
                int note = (int)((mouseX - leftPos - NOTE_AREA.getX() - horizontalScroll.getChaseTarget()) / noteWidth);
                if (note < 0 || note >= program.getLength()) return super.mouseClicked(mouseX, mouseY, button);
                dragging = true;
                draggingChannel = channelNo;
                draggingDeleting = channel.getStrength(note) != 0;
                channel.setStrength(note, draggingDeleting ? 0 : 15);
                shouldSend = true;
                return true;
            } else if (inItemArea) {
                boolean success = false;
                if (mX > ITEM_AREA.getX() + 4) {
                    if (mX < ITEM_AREA.getX() + 16) {
                        program.remove(channel);
                        clampVerticalScroll(verticalScroll);
                        success = true;
                    } else if (mX < ITEM_AREA.getX() + 32 && channelNo < channels.size() - 1) {
                        program.swap(channel, channels.get(channelNo + 1));
                        success = true;
                    };
                };
                if (success) {
                    menu.refreshSlots();
                    shouldSend = true;
                    return true;
                };
            };
        };

        dragging = false;

        return super.mouseClicked(mouseX, mouseY, button);
    };

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {

        if (dragging && NOTE_AREA.contains((int)mouseX, (int)mouseY)) {
            ImmutableList<Channel> channels = program.getChannels();
            if (draggingChannel < 0 || draggingChannel >= channels.size()) return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
            Channel channel = channels.get(draggingChannel);
            int leftNote = (int)((mouseX - leftPos - NOTE_AREA.getX() - horizontalScroll.getChaseTarget()) / noteWidth);
            for (int i = 0; i <= Math.abs(dragX) / noteWidth; i++) {
                int note = leftNote + i * (int)Math.signum(dragX);
                if (note < 0 || note >= program.getLength()) continue;
                channel.setStrength(note, draggingDeleting ? 0 : 15);
            };
            shouldSend = true;
            return true;
        };

        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    };

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    };

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int mX = (int)mouseX - getGuiLeft();
        int mY = (int)mouseY - getGuiTop();
        if (ITEM_AREA.contains(mX, mY)) {
            int oldScroll = verticalScroll;
            clampVerticalScroll(oldScroll + (int)delta * 5);
            if (oldScroll != verticalScroll) {
                menu.refreshSlots(verticalScroll);
                shouldSend = true;
            };
            return true;
        };
        return super.mouseScrolled(mouseX, mouseY, delta);
    };

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {

        PoseStack ms = graphics.pose();
        ms.pushPose();

        // Background
        background.render(graphics, leftPos, topPos);

        ms.translate(leftPos, topPos, 0d);

        float xOffset = horizontalScroll.getValue(partialTicks);
        float yOffset = -verticalScroll;

        // Labels
        graphics.drawString(font, DestroyLang.translate("tooltip.redstone_programmer.playback").component(), 6, background.height - 34, AllGuiTextures.FONT_COLOR, false);
        graphics.drawString(font, DestroyLang.translate("tooltip.redstone_programmer.editor").component(), 156, background.height - 34, AllGuiTextures.FONT_COLOR, false);

        // Scroll values
        graphics.drawString(font, Component.literal(""+program.getTicksPerBeat()), 9, background.height - 19, 0xE0E0E0, true);
        graphics.drawString(font, Component.literal(""+program.beatsPerLine), 159, background.height - 19, 0xE0E0E0, true);
        graphics.drawString(font, Component.literal(""+program.linesPerBar), 179, background.height - 19, 0xE0E0E0, true);

        UIRenderHelper.swapAndBlitColor(minecraft.getMainRenderTarget(), UIRenderHelper.framebuffer);

        // Lines
        if (program.beatsPerLine > 0) {
            GuiHelper.startStencil(graphics, NOTE_AREA.getX(), NOTE_AREA.getY() - 11, NOTE_AREA.getWidth(), NOTE_AREA.getHeight() + 11);
            ms.pushPose();
            ms.translate(NOTE_AREA.getX(), NOTE_AREA.getY() - 10, 0f);
            int time = 0;
            while (time < program.getLength()) {
                float horizontalOffset = xOffset + time * noteWidth;
                if (horizontalOffset < 0f || horizontalOffset > NOTE_AREA.getWidth()) {
                    time += program.beatsPerLine;
                    continue;
                };
                ms.pushPose();
                ms.translate(horizontalOffset, 0f, 0f);
                DestroyGuiTextures line = program.linesPerBar > 0 && time % (program.linesPerBar * program.beatsPerLine) == 0 ? DestroyGuiTextures.REDSTONE_PROGRAMMER_BARLINE : DestroyGuiTextures.REDSTONE_PROGRAMMER_LINE;
                line.render(graphics, 0, 0);
                ms.popPose();
                time += program.beatsPerLine;
            };
            ms.pushPose();
            ms.translate(xOffset + program.getLength() * noteWidth, 0f, 0f);
            DestroyGuiTextures.REDSTONE_PROGRAMMER_BARLINE.render(graphics, 0, 0);
            ms.popPose();
            ms.popPose();
            GuiHelper.endStencil();
        };
        
        // Channels
        int channelNo = 0;
        for (Channel channel : program.getChannels()) {
            float verticalOffset = yOffset + channelNo * distanceBetweenChannels;
            if (verticalOffset < -distanceBetweenChannels || verticalOffset > ITEM_AREA.getHeight()) {
                channelNo++;
                continue;
            };

            // Items and buttons
            GuiHelper.startStencil(graphics, ITEM_AREA.getX(), ITEM_AREA.getY(), ITEM_AREA.getWidth(), ITEM_AREA.getHeight());
            ms.pushPose();
            ms.translate(ITEM_AREA.getX(), ITEM_AREA.getY() + verticalOffset, 0d);
            DestroyGuiTextures.REDSTONE_PROGRAMMER_ITEM_SLOTS.render(graphics, 31, 3);
            DestroyGuiTextures.REDSTONE_PROGRAMMER_DELETE_CHANNEL.render(graphics, 2, 3);
            if (channelNo < program.getChannels().size() - 1) DestroyGuiTextures.REDSTONE_PROGRAMMER_MOVE_CHANNEL_DOWN.render(graphics, 16, 8);
            graphics.renderItem(channel.getNetworkKey().getFirst().getStack(), 32, 4);
            graphics.renderItem(channel.getNetworkKey().getSecond().getStack(), 50, 4);
            ms.popPose();
            GuiHelper.endStencil();

            // Sequence
            GuiHelper.startStencil(graphics, NOTE_AREA.getX(), NOTE_AREA.getY(), NOTE_AREA.getWidth(), NOTE_AREA.getHeight());
            renderNotes: for (int i = 0; i < program.getLength(); i++) {
                float horizontalOffset = xOffset + i * noteWidth;
                if (horizontalOffset < 0 || horizontalOffset > NOTE_AREA.getWidth()) break renderNotes;
                int strength = channel.getStrength(i);
                if (strength == 0) continue;

                boolean previousMatches = i == 0 ? false : channel.getStrength(i - 1) == strength;
                boolean nextMatches = i == program.getLength() ? false : channel.getStrength(i + 1) == strength;

                DestroyGuiTextures border = previousMatches ? (nextMatches ? DestroyGuiTextures.REDSTONE_PROGRAMMER_NOTE_BORDER_MIDDLE : DestroyGuiTextures.REDSTONE_PROGRAMMER_NOTE_BORDER_RIGHT) : (nextMatches ? DestroyGuiTextures.REDSTONE_PROGRAMMER_NOTE_BORDER_LEFT : DestroyGuiTextures.REDSTONE_PROGRAMMER_NOTE_BORDER_LONE);
                
                ms.pushPose();
                ms.translate(NOTE_AREA.getX() + horizontalOffset, NOTE_AREA.getY() + verticalOffset, 0);
                DestroyGuiTextures.getRedstoneProgrammerNote(strength).render(graphics, 0, 2);
                border.render(graphics, 0, 2);
                ms.popPose();
            };
            GuiHelper.endStencil();
            channelNo++;
        };

        // Additional item slots for adding a new channel
        GuiHelper.startStencil(graphics, ITEM_AREA.getX(), ITEM_AREA.getY(), ITEM_AREA.getWidth(), ITEM_AREA.getHeight());
        if (channelNo < DestroyAllConfigs.SERVER.contraptions.maxChannels.get()) {
            ms.pushPose();
            ms.translate(ITEM_AREA.getX(), NOTE_AREA.getY() + yOffset + channelNo * distanceBetweenChannels, 0f);
            DestroyGuiTextures.REDSTONE_PROGRAMMER_ITEM_SLOTS.render(graphics, 31, 3);
            ms.popPose();
        };
        GuiHelper.endStencil();

        // Playhead and length buttons
        GuiHelper.startStencil(graphics, NOTE_AREA.getX(), NOTE_AREA.getY() - 11, NOTE_AREA.getWidth(), NOTE_AREA.getHeight() + 11);
        ms.pushPose();
        ms.translate(NOTE_AREA.getX() - horizontalScroll.getValue(partialTicks), NOTE_AREA.getY() - 10, channelNo);

        DestroyGuiTextures.REDSTONE_PROGRAMMER_REMOVE_BAR.render(graphics, 80, 80);
        DestroyGuiTextures.REDSTONE_PROGRAMMER_ADD_BAR.render(graphics, 96, 80);

        ms.pushPose();
        ms.translate(program.paused ? playhead.getValue() : playhead.getValue(partialTicks), 0f, 0f);
        DestroyGuiTextures.REDSTONE_PROGRAMMER_PLAYHEAD.render(graphics, -3, 0);
        ms.popPose();

        ms.popPose();

        GuiHelper.endStencil();

        // Shadows
        graphics.fillGradient(ITEM_AREA.getX(), ITEM_AREA.getY(), ITEM_AREA.getX() + ITEM_AREA.getWidth(), ITEM_AREA.getY() + 10, 200, 0x77000000, 0x00000000);
        graphics.fillGradient(ITEM_AREA.getX(), ITEM_AREA.getY() + ITEM_AREA.getHeight() - 10, ITEM_AREA.getX() + ITEM_AREA.getWidth(), ITEM_AREA.getY() + ITEM_AREA.getHeight(), 200, 0x00000000, 0x70000000);

        UIRenderHelper.swapAndBlitColor(UIRenderHelper.framebuffer, minecraft.getMainRenderTarget());

        ms.popPose();
    };

    public void clampVerticalScroll(int newScroll) {
        verticalScroll = Mth.clamp(newScroll, 0, Math.max(0, 6 + Math.min(program.getChannels().size() + 1, DestroyAllConfigs.SERVER.contraptions.maxChannels.get()) * distanceBetweenChannels - ITEM_AREA.getHeight()));
    };

    public void setPlayPauseButtonIcon() {
        playPauseButton.setIcon(program.paused ? AllIcons.I_PLAY : AllIcons.I_PAUSE);
    };
    
};
