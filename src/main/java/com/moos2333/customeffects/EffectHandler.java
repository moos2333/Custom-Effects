package com.moos2333.customeffects;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumHand;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class EffectHandler {

    private static final String NBT_ROOT = "ce";

    private static final int REFRESH_INTERVAL = 20;
    private static final int REFRESH_DURATION = 40;

    private static final EntityEquipmentSlot[] ARMOR_SLOTS = {
            EntityEquipmentSlot.HEAD,
            EntityEquipmentSlot.CHEST,
            EntityEquipmentSlot.LEGS,
            EntityEquipmentSlot.FEET
    };

    private static final Map<String, SlotPlan[]> PLANS = buildPlans();

    private EffectHandler() {}

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        EntityLivingBase victim = event.getEntityLiving();
        if (victim.world.isRemote) return;

        Entity trueSource = event.getSource().getTrueSource();
        if (trueSource instanceof EntityLivingBase) {
            EntityLivingBase attacker = (EntityLivingBase) trueSource;
            trigger(attacker, "AttackEffect", victim, 0);
            if (isMelee(event.getSource())) {
                trigger(attacker, "MeleeEffect", victim, 0);
            }
        }
        trigger(victim, "HurtEffect", null, 0);
    }

    @SubscribeEvent
    public static void onLivingUpdate(LivingEvent.LivingUpdateEvent event) {
        if (!(event.getEntityLiving() instanceof EntityPlayer)) return;
        EntityPlayer player = (EntityPlayer) event.getEntityLiving();
        if (player.world.isRemote || player.ticksExisted % REFRESH_INTERVAL != 0) return;
        trigger(player, "WearEffect", null, REFRESH_DURATION);
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getWorld().isRemote) return;
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        NBTTagCompound ce = readCe(stack);
        if (ce == null) return;

        EntityPlayer player = event.getEntityPlayer();
        applyKey(ce, "UseEffect_all", player, 0);
        if (event.getHand() == EnumHand.MAIN_HAND) {
            applyKey(ce, "UseEffect", player, 0);
            applyKey(ce, "UseEffect_mainhand", player, 0);
        } else {
            applyKey(ce, "UseEffect_offhand", player, 0);
        }
    }

    private static void trigger(EntityLivingBase holder, String base, EntityLivingBase enemy, int minDuration) {
        for (SlotPlan plan : PLANS.get(base)) {
            ItemStack stack = plan.extractor.apply(holder);
            if (stack.isEmpty()) continue;
            NBTTagCompound ce = readCe(stack);
            if (ce == null) continue;
            for (int i = 0; i < plan.keys.length; i++) {
                applyKey(ce, plan.keys[i], plan.self[i] ? holder : enemy, minDuration);
            }
        }
    }

    private static NBTTagCompound readCe(ItemStack stack) {
        NBTTagCompound root = stack.getTagCompound();
        if (root == null) return null;
        NBTBase raw = root.getTag(NBT_ROOT);
        return raw instanceof NBTTagCompound ? (NBTTagCompound) raw : null;
    }

    private static void applyKey(NBTTagCompound ce, String key, EntityLivingBase receiver, int minDuration) {
        if (receiver == null) return;
        NBTBase raw = ce.getTag(key);
        if (!(raw instanceof NBTTagIntArray)) return;
        int[] data = ((NBTTagIntArray) raw).getIntArray();
        if (data.length < 3) return;
        Potion potion = Potion.getPotionById(data[0]);
        if (potion == null) return;
        int duration = Math.max(data[1], minDuration);
        if (duration <= 0) return;
        int amplifier = Math.max(0, data[2]);

        PotionEffect existing = receiver.getActivePotionEffect(potion);
        if (existing != null) {
            if (existing.getAmplifier() > amplifier) return;
            if (existing.getAmplifier() == amplifier && existing.getDuration() >= duration) return;
        }
        receiver.addPotionEffect(new PotionEffect(potion, duration, amplifier));
    }

    private static boolean isMelee(DamageSource source) {
        Entity immediate = source.getImmediateSource();
        return immediate != null && immediate == source.getTrueSource();
    }

    private static Map<String, SlotPlan[]> buildPlans() {
        Map<String, SlotPlan[]> map = new HashMap<>();

        for (String base : new String[]{"AttackEffect", "MeleeEffect"}) {
            List<SlotPlan> list = new ArrayList<>();
            list.add(new SlotPlan(
                    EntityLivingBase::getHeldItemMainhand,
                    new String[]{base, base + "_self", base + "_mainhand", base + "_mainhand_self"},
                    new boolean[]{false, true, false, true}
            ));
            list.add(new SlotPlan(
                    EntityLivingBase::getHeldItemOffhand,
                    new String[]{base + "_offhand", base + "_offhand_self"},
                    new boolean[]{false, true}
            ));
            for (EntityEquipmentSlot slot : ARMOR_SLOTS) {
                String s = slotName(slot);
                list.add(new SlotPlan(
                        e -> e.getItemStackFromSlot(slot),
                        new String[]{base + "_" + s, base + "_" + s + "_self"},
                        new boolean[]{false, true}
                ));
            }
            String[] allKeys = {base + "_all", base + "_all_self"};
            boolean[] allSelf = {false, true};
            list.add(new SlotPlan(EntityLivingBase::getHeldItemMainhand, allKeys, allSelf));
            list.add(new SlotPlan(EntityLivingBase::getHeldItemOffhand, allKeys, allSelf));
            for (EntityEquipmentSlot slot : ARMOR_SLOTS) {
                list.add(new SlotPlan(e -> e.getItemStackFromSlot(slot), allKeys, allSelf));
            }
            map.put(base, list.toArray(new SlotPlan[0]));
        }

        for (String base : new String[]{"WearEffect", "HurtEffect"}) {
            List<SlotPlan> list = new ArrayList<>();
            list.add(new SlotPlan(
                    EntityLivingBase::getHeldItemMainhand,
                    new String[]{base, base + "_mainhand"},
                    new boolean[]{true, true}
            ));
            list.add(new SlotPlan(
                    EntityLivingBase::getHeldItemOffhand,
                    new String[]{base + "_offhand"},
                    new boolean[]{true}
            ));
            for (EntityEquipmentSlot slot : ARMOR_SLOTS) {
                String s = slotName(slot);
                list.add(new SlotPlan(
                        e -> e.getItemStackFromSlot(slot),
                        new String[]{base + "_" + s},
                        new boolean[]{true}
                ));
            }
            String[] allKeys = {base + "_all"};
            boolean[] allSelf = {true};
            list.add(new SlotPlan(EntityLivingBase::getHeldItemMainhand, allKeys, allSelf));
            list.add(new SlotPlan(EntityLivingBase::getHeldItemOffhand, allKeys, allSelf));
            for (EntityEquipmentSlot slot : ARMOR_SLOTS) {
                list.add(new SlotPlan(e -> e.getItemStackFromSlot(slot), allKeys, allSelf));
            }
            map.put(base, list.toArray(new SlotPlan[0]));
        }

        return Collections.unmodifiableMap(map);
    }

    private static String slotName(EntityEquipmentSlot slot) {
        switch (slot) {
            case HEAD:  return "head";
            case CHEST: return "chest";
            case LEGS:  return "legs";
            case FEET:  return "feet";
            default:    return "mainhand";
        }
    }

    private static final class SlotPlan {
        final Function<EntityLivingBase, ItemStack> extractor;
        final String[] keys;
        final boolean[] self;
        SlotPlan(Function<EntityLivingBase, ItemStack> extractor, String[] keys, boolean[] self) {
            this.extractor = extractor;
            this.keys = keys;
            this.self = self;
        }
    }
}