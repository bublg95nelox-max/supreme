package net.frontier.items;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class FrontierItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, "frontier");
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, "frontier");

    public static final DeferredHolder<Item, DetectiveKitItem> DETECTIVE_KIT = ITEMS.register("detective_kit",
            () -> new DetectiveKitItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, EvidenceNoteItem> EVIDENCE_NOTE = ITEMS.register("evidence_note",
            () -> new EvidenceNoteItem(new Item.Properties().stacksTo(16)));

    public static final DeferredHolder<Item, SurveyorToolItem> SURVEYOR_TOOL = ITEMS.register("surveyor_tool",
            () -> new SurveyorToolItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> FRONTIER_TAB = CREATIVE_MODE_TABS.register("frontier_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.literal("Frontier"))
                    .icon(() -> new ItemStack(DETECTIVE_KIT.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(DETECTIVE_KIT.get());
                        output.accept(EVIDENCE_NOTE.get());
                        output.accept(SURVEYOR_TOOL.get());
                    })
                    .build());

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
    }
}
