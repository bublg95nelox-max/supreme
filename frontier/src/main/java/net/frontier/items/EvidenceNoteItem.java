package net.frontier.items;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;

import java.util.List;

public class EvidenceNoteItem extends Item {
    public EvidenceNoteItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData != null && !customData.isEmpty()) {
            CompoundTag tag = customData.copyTag();
            int clueId = tag.getInt("ClueId");
            String stateName = tag.getString("StateName");
            String crimeType = tag.getString("CrimeType");
            String grade = tag.getString("Grade");
            String desc = tag.getString("Desc");
            String tool = tag.getString("Tool");
            String actor = tag.getString("ActorName");

            tooltip.add(Component.literal("§6Улика №" + clueId + " §7[" + stateName + "]").withStyle(ChatFormatting.GOLD));
            tooltip.add(Component.literal("§7Тип: §e" + crimeType));
            tooltip.add(Component.literal("§7Достоверность: §f" + grade));
            tooltip.add(Component.literal("§7Подозреваемый: §c" + (actor.isEmpty() ? "Неизвестен" : actor)));
            if (!tool.isEmpty()) {
                tooltip.add(Component.literal("§7Орудие: §b" + tool));
            }
            if (!desc.isEmpty()) {
                tooltip.add(Component.literal("§8" + desc).withStyle(ChatFormatting.ITALIC));
            }
            tooltip.add(Component.literal("§d[Официальный документ для судебного разбирательства]").withStyle(ChatFormatting.DARK_PURPLE));
        } else {
            tooltip.add(Component.literal("§7Пустой бланк судебного протокола.").withStyle(ChatFormatting.GRAY));
        }
    }
}
