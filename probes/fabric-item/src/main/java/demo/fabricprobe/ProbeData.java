package demo.fabricprobe;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

/** Probe-owned vanilla SavedData: proves the registered item survives a world save/reload. */
public final class ProbeData extends SavedData {
    public static final Factory<ProbeData> FACTORY = new Factory<>(ProbeData::new, ProbeData::load, null);
    public ItemStack stack = new ItemStack(ItemProbe.ITEM, 3);
    public int launches;
    private static ProbeData load(CompoundTag tag, HolderLookup.Provider registries) {
        ProbeData data = new ProbeData();
        data.stack = ItemStack.parse(registries, tag.getCompound("stack")).orElseThrow();
        data.launches = tag.getInt("launches");
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("stack", stack.save(registries)); tag.putInt("launches", launches); return tag;
    }
}
