package me.ronaldi2001.modutils.nms.compat.jade.providers;

import me.ronaldi2001.modutils.nms.ServerApi;
import me.ronaldi2001.modutils.nms.Nms;
import me.ronaldi2001.modutils.nms.compat.jade.EntityCtx;
import me.ronaldi2001.modutils.nms.compat.jade.JadeEntityProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.item.ItemStack;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Tameable;

import java.util.List;
import java.util.UUID;

/** Server-side halves of Jade's vanilla entity providers. Data layouts mirror Jade's stream codecs. */
public final class EntityProviders {
	/** Jade ignores timers further away than this (two days). */
	private static final int MAX_DROP_TICKS = 24000 * 2;

	private EntityProviders() {
	}

	public static List<JadeEntityProvider> all() {
		return List.of(
				new MobGrowthProvider(),
				new MobBreedingProvider(),
				new AnimalOwnerProvider(),
				new NextEntityDropProvider(),
				new EntityItemStorageProvider()
		);
	}

	private static final class MobGrowthProvider implements JadeEntityProvider {
		@Override
		public Identifier id() {
			return JadeIds.MOB_GROWTH;
		}

		@Override
		public String configKey() {
			return "mobGrowth";
		}

		@Override
		public void append(EntityCtx ctx) {
			if (!(ctx.entity() instanceof AgeableMob ageable)) return;

			int time = -ageable.getAge();
			if (time > 0) ctx.put(id(), buf -> buf.writeVarInt(time));
		}
	}

	private static final class MobBreedingProvider implements JadeEntityProvider {
		@Override
		public Identifier id() {
			return JadeIds.MOB_BREEDING;
		}

		@Override
		public String configKey() {
			return "mobBreeding";
		}

		@Override
		public void append(EntityCtx ctx) {
			if (!(ctx.entity() instanceof Animal animal)) return;

			int time = animal.getAge();
			if (time > 0) ctx.put(id(), buf -> buf.writeVarInt(time));
		}
	}

	private static final class AnimalOwnerProvider implements JadeEntityProvider {
		@Override
		public Identifier id() {
			return JadeIds.ANIMAL_OWNER;
		}

		@Override
		public String configKey() {
			return "animalOwner";
		}

		@Override
		public void append(EntityCtx ctx) {
			if (!(ctx.entity().getBukkitEntity() instanceof Tameable tameable)) return;

			UUID ownerId = ServerApi.ownerId(tameable);
			if (ownerId == null) return;

			OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerId);
			String name = owner.getName();
			if (name == null) return;

			// Jade 15 (1.21, 1.21.1) sends the owner's name as plain text, the newer ones as a component
			if (ctx.beforeJade7()) {
				ctx.put(id(), buf -> buf.writeUtf(name));
			} else {
				ctx.put(id(), buf -> ComponentSerialization.STREAM_CODEC.encode(buf, Component.literal(name)));
			}
		}
	}

	private static final class NextEntityDropProvider implements JadeEntityProvider {
		@Override
		public Identifier id() {
			return JadeIds.NEXT_ENTITY_DROP;
		}

		@Override
		public String configKey() {
			return "nextEntityDrop";
		}

		@Override
		public void append(EntityCtx ctx) {
			if (ctx.entity().getBukkitEntity() instanceof Chicken chicken && chicken.isAdult()) {
				int time = ServerApi.eggLayTime(chicken);
				if (time < MAX_DROP_TICKS) ctx.tag().putInt("NextEggIn", time);
			}
		}
	}

	/** Minecart chests, hopper minecarts, chest boats and anything else that is an entity container. */
	private static final class EntityItemStorageProvider implements JadeEntityProvider {
		@Override
		public Identifier id() {
			return JadeIds.ITEM_STORAGE;
		}

		@Override
		public String configKey() {
			return "entityItemStorage";
		}

		@Override
		public void append(EntityCtx ctx) {
			List<ItemStack> stacks = ItemStorageSupport.entityItems(ctx);
			if (stacks != null) ctx.put(id(), buf -> ItemStorageSupport.write(buf, ctx.items(), stacks));
		}
	}
}
