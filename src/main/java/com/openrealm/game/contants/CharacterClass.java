package com.openrealm.game.contants;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.openrealm.game.entity.Player;

public enum CharacterClass {
    ROGUE(0), ARCHER(1), WIZARD(2), PRIEST(3), WARRIOR(4), KNIGHT(5), PALLADIN(6),
    ASSASSIN(7), NECROMANCER(8), MYSTIC(9), TRICKSTER(10), SORCERER(11), HUNTRESS(12),
    NINJA(13),

    ROBE(-1), LEATHER(-2), HEAVY(-3), ALL(-4),
    STAFF_USER(-5), WAND_USER(-6), DAGGER_USER(-7), BOW_USER(-8);

    public static Map<Integer, CharacterClass> map = new HashMap<>();
    static {
        for (CharacterClass cc : CharacterClass.values()) {
            CharacterClass.map.put(cc.classId, cc);
        }
    }

    public int classId;

    CharacterClass(int classId) {
        this.classId = classId;
    }

    public static List<CharacterClass> getCharacterClasses() {
        return Arrays.asList(CharacterClass.values()).stream().filter(c -> c.classId >= 0).collect(Collectors.toList());
    }

    public static boolean isRobeClass(CharacterClass c) {
        return c.equals(CharacterClass.WIZARD) || c.equals(CharacterClass.PRIEST)
            || c.equals(CharacterClass.NECROMANCER) || c.equals(CharacterClass.MYSTIC)
            || c.equals(CharacterClass.SORCERER);
    }

    public static boolean isLeatherClass(CharacterClass c) {
        return c.equals(CharacterClass.ARCHER) || c.equals(CharacterClass.ROGUE)
            || c.equals(CharacterClass.ASSASSIN) || c.equals(CharacterClass.TRICKSTER)
            || c.equals(CharacterClass.HUNTRESS) || c.equals(CharacterClass.NINJA);
    }

    public static boolean isHeavyClass(CharacterClass c) {
        return c.equals(CharacterClass.WARRIOR) || c.equals(CharacterClass.PALLADIN) || c.equals(CharacterClass.KNIGHT);
    }

    public static boolean isPlayerHeavyClass(Player p) {
        return CharacterClass.isHeavyClass(CharacterClass.valueOf(p.getClassId()));
    }

    public static boolean isPlayerLeatherClass(Player p) {
        return CharacterClass.isLeatherClass(CharacterClass.valueOf(p.getClassId()));
    }

    public static boolean isPlayerRobeClass(Player p) {
        return CharacterClass.isRobeClass(CharacterClass.valueOf(p.getClassId()));
    }

    public static CharacterClass getPlayerCharacterClass(Player p) {
        return CharacterClass.valueOf(p.getClassId());
    }

    public static boolean isStaffUser(CharacterClass c) {
        return c.equals(WIZARD) || c.equals(NECROMANCER) || c.equals(MYSTIC);
    }

    public static boolean isWandUser(CharacterClass c) {
        return c.equals(PRIEST) || c.equals(SORCERER);
    }

    public static boolean isDaggerUser(CharacterClass c) {
        return c.equals(ROGUE) || c.equals(ASSASSIN) || c.equals(TRICKSTER) || c.equals(NINJA);
    }

    public static boolean isBowUser(CharacterClass c) {
        return c.equals(ARCHER) || c.equals(HUNTRESS);
    }

    public static boolean isValidUser(Player p, byte requiredClass) {
        CharacterClass req = CharacterClass.valueOf(requiredClass);
        CharacterClass playerClass = CharacterClass.getPlayerCharacterClass(p);
        boolean result = false;
        switch (req) {
        case ROBE:
            result = CharacterClass.isRobeClass(playerClass);
            break;
        case LEATHER:
            result = CharacterClass.isLeatherClass(playerClass);
            break;
        case HEAVY:
            result = CharacterClass.isHeavyClass(playerClass);
            break;
        case ALL:
            result = true;
            break;
        case STAFF_USER:
            result = CharacterClass.isStaffUser(playerClass);
            break;
        case WAND_USER:
            result = CharacterClass.isWandUser(playerClass);
            break;
        case DAGGER_USER:
            result = CharacterClass.isDaggerUser(playerClass);
            break;
        case BOW_USER:
            result = CharacterClass.isBowUser(playerClass);
            break;
        default:
            result = req.equals(playerClass);
        }
        return result;
    }

    public static CharacterClass valueOf(int classId) {
        return CharacterClass.map.get(classId);
    }

}
