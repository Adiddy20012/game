package gg.rsmod.plugins.content.combat.strategy

import gg.rsmod.game.model.Tile
import gg.rsmod.game.model.attr.LAST_KNOWN_WEAPON_TYPE
import gg.rsmod.game.model.combat.AttackStyle
import gg.rsmod.game.model.combat.PawnHit
import gg.rsmod.game.model.combat.XpMode
import gg.rsmod.game.model.entity.GroundItem
import gg.rsmod.game.model.entity.Npc
import gg.rsmod.game.model.entity.Pawn
import gg.rsmod.game.model.entity.Player
import gg.rsmod.plugins.api.EquipmentType
import gg.rsmod.plugins.api.HitType
import gg.rsmod.plugins.api.Skills
import gg.rsmod.plugins.api.WeaponType
import gg.rsmod.plugins.api.cfg.Items
import gg.rsmod.plugins.api.ext.getEquipment
import gg.rsmod.plugins.api.ext.hasEquipped
import gg.rsmod.plugins.api.ext.hasWeaponType
import gg.rsmod.plugins.api.ext.message
import gg.rsmod.plugins.content.combat.Combat
import gg.rsmod.plugins.content.combat.CombatConfigs
import gg.rsmod.plugins.content.combat.createProjectile
import gg.rsmod.plugins.content.combat.dealHit
import gg.rsmod.plugins.content.combat.formula.RangedCombatFormula
import gg.rsmod.plugins.content.combat.strategy.ranged.RangedProjectile
import gg.rsmod.plugins.content.combat.strategy.ranged.ammo.Darts
import gg.rsmod.plugins.content.combat.strategy.ranged.ammo.Knives
import gg.rsmod.plugins.content.combat.strategy.ranged.weapon.BowType
import gg.rsmod.plugins.content.combat.strategy.ranged.weapon.Bows
import gg.rsmod.plugins.content.combat.strategy.ranged.weapon.CrossbowType

/**
 * @author Tom <rspsmods@gmail.com>
 */
object RangedCombatStrategy : CombatStrategy {

    private const val DEFAULT_ATTACK_RANGE = 7

    private const val MAX_ATTACK_RANGE = 10

    override fun getAttackRange(pawn: Pawn): Int {
        if (pawn is Player) {
            val weapon = pawn.getEquipment(EquipmentType.WEAPON)
            val attackStyle = CombatConfigs.getAttackStyle(pawn)

            var range = when (weapon?.id) {
                Items.CHINCHOMPA_10033, Items.RED_CHINCHOMPA_10034 -> 9
                in Bows.LONG_BOWS -> 9
                in Knives.KNIVES -> 6
                in Darts.DARTS -> 3
                in Bows.CRYSTAL_BOWS -> 10
                else -> DEFAULT_ATTACK_RANGE
            }

            if (attackStyle == AttackStyle.LONG_RANGE) {
                range += 2
            }

            return Math.min(MAX_ATTACK_RANGE, range)
        }
        return DEFAULT_ATTACK_RANGE
    }

    override fun canAttack(pawn: Pawn, target: Pawn): Boolean {
        if (pawn is Player) {
            val weapon = pawn.getEquipment(EquipmentType.WEAPON)
            val ammo = pawn.getEquipment(EquipmentType.AMMO)

            val crossbow = CrossbowType.values.firstOrNull { it.item == weapon?.id }
            if (crossbow != null && ammo?.id !in crossbow.ammo) {
                val message = if (ammo != null) "You can't use that ammo with your crossbow." else "There is no ammo left in your quiver."
                pawn.message(message)
                pawn.resetFacePawn()
                return false
            }

            val bow = BowType.values.firstOrNull { it.item == weapon?.id }
            if (bow != null && bow.ammo.isNotEmpty() && ammo?.id !in bow.ammo) {
                val message = if (ammo != null) "You can't use that ammo with your bow." else "There is no ammo left in your quiver."
                pawn.message(message)
                pawn.resetFacePawn()
                return false
            }
        }
        return true
    }

    override fun attack(pawn: Pawn, target: Pawn) {
        val world = pawn.world

        val animation = CombatConfigs.getAttackAnimation(pawn)

        /*
         * A list of actions that will be executed upon this hit dealing damage
         * to the [target].
         */
        var ammoDropAction: ((PawnHit).() -> Unit) = {}

        if (pawn is Player) {

            /*
             * Get the [EquipmentType] for the ranged weapon you're using.
             */
            val ammoSlot = when {
                pawn.hasWeaponType(WeaponType.THROWN) || pawn.hasWeaponType(WeaponType.CHINCHOMPA) -> EquipmentType.WEAPON
                else -> EquipmentType.AMMO
            }

            val ammo = pawn.getEquipment(ammoSlot)

            /*
             * Create a projectile based on ammo.
             */
            val ammoProjectile = if (ammo != null) RangedProjectile.values.firstOrNull { ammo.id in it.items } else null
            if (ammoProjectile != null) {
                val projectile = pawn.createProjectile(target, ammoProjectile.gfx, ammoProjectile.type)
                ammoProjectile.drawback?.let { drawback -> pawn.graphic(drawback) }
                ammoProjectile.impact?.let { impact -> target.graphic(impact.id, impact.height, projectile.lifespan) }
                world.spawn(projectile)
            }

            /*
             * Remove or drop ammo if applicable.
             */
            if (ammo != null && (ammoProjectile == null || !ammoProjectile.breakOnImpact())) {
                val chance = world.random(99)
                val breakAmmo = chance in 0..19
                val dropAmmo = when {
                    pawn.hasEquipped(EquipmentType.CAPE, Items.AVAS_ACCUMULATOR) -> chance in 20..27
                    else -> !breakAmmo
                }

                val amount = 1
                if (breakAmmo || dropAmmo) {
                    pawn.equipment.remove(ammo.id, amount)
                }
                if (dropAmmo) {
                    ammoDropAction = { world.spawn(GroundItem(ammo.id, amount, target.tile, pawn)) }
                }
            }

            if (pawn.hasWeaponType(WeaponType.THROWN) || pawn.hasWeaponType(WeaponType.CHINCHOMPA)) {
                if (pawn.getEquipment(EquipmentType.WEAPON) == null) {
                    pawn.message("You do not have enough ammo left.")
                    pawn.attr[LAST_KNOWN_WEAPON_TYPE] = 0
                }
            }
        }
        pawn.animate(animation)

        val formula = RangedCombatFormula
        val accuracy = formula.getAccuracy(pawn, target)
        val maxHit = formula.getMaxHit(pawn, target)
        val landHit = accuracy >= world.randomDouble()
        val hitDelay = getHitDelay(pawn.getCentreTile(), target.tile.transform(target.getSize() / 2, target.getSize() / 2))
        val damage = pawn.dealHit(
            target = target,
            maxHit = maxHit,
            landHit = landHit,
            delay = hitDelay,
            onHit = ammoDropAction,
            hitType = HitType.RANGE
        ).hit.hitmarks.sumOf { it.damage }

        if (damage > 0 && pawn.entityType.isPlayer) {
            addCombatXp(pawn as Player, target, damage)
        }
    }

    fun getHitDelay(start: Tile, target: Tile): Int {
        val distance = start.getDistance(target)
        return 2 + (Math.floor((3.0 + distance) / 6.0)).toInt()
    }

    private fun addCombatXp(player: Player, target: Pawn, damage: Int) {
        val modDamage = if (target.entityType.isNpc) Math.min(target.getCurrentHp(), damage) else damage
        val mode = CombatConfigs.getXpMode(player)
        val multiplier = if (target is Npc) Combat.getNpcXpMultiplier(target) else 1.0

        val hitpointsExperience = (modDamage * 0.133)*multiplier
        val combatExperience = (modDamage * 0.4)*multiplier
        val sharedExperience = (modDamage * 0.2)*multiplier

        if (mode == XpMode.RANGED) {
            player.addXp(Skills.RANGED, combatExperience)
        } else if (mode == XpMode.SHARED) {
            player.addXp(Skills.RANGED, sharedExperience)
            player.addXp(Skills.DEFENCE, sharedExperience)
        }
        player.addXp(Skills.HITPOINTS, hitpointsExperience)
    }
}