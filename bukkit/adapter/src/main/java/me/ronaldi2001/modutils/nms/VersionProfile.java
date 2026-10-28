package me.ronaldi2001.modutils.nms;

import com.mojang.authlib.GameProfile;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * A player's profile. authlib's GameProfile is a record from 1.21.9 on ({@code id()}, {@code name()}) and has getters
 * before ({@code getId()}, {@code getName()}), which neither form compiles against the other. It is read once per join, so
 * the methods are called by name.
 */
final class VersionProfile {
	private static final Method ID = find("id", "getId");
	private static final Method NAME = find("name", "getName");

	private VersionProfile() {
	}

	private static Method find(String record, String getter) {
		try {
			return GameProfile.class.getMethod(record);
		} catch (NoSuchMethodException e) {
			try {
				return GameProfile.class.getMethod(getter);
			} catch (NoSuchMethodException ex) {
				throw new ExceptionInInitializerError(ex);
			}
		}
	}

	static UUID id(GameProfile profile) {
		try {
			return (UUID) ID.invoke(profile);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	static String name(GameProfile profile) {
		try {
			return String.valueOf(NAME.invoke(profile));
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}
}
