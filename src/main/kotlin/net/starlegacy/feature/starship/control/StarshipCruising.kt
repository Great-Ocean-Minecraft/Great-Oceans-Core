package net.starlegacy.feature.starship.control

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign
import net.starlegacy.SLComponent
import net.starlegacy.feature.space.SpaceWorlds
import net.starlegacy.feature.starship.PilotedStarships
import net.starlegacy.feature.starship.active.ActivePlayerStarship
import net.starlegacy.feature.starship.active.ActiveStarships
import net.starlegacy.feature.starship.event.StarshipStartCruisingEvent
import net.starlegacy.feature.starship.event.StarshipStopCruisingEvent
import net.starlegacy.feature.starship.movement.TranslateMovement
import net.starlegacy.util.Tasks
import net.starlegacy.util.action
import net.starlegacy.util.msg
import net.starlegacy.util.roundToHundredth
import org.bukkit.entity.Player
import org.bukkit.util.Vector

object StarshipCruising : SLComponent() {
	const val SECONDS_PER_CRUISE = 2.0

	class CruiseData(
		starship: ActivePlayerStarship,
		var velocity: Vector = Vector(),
		var targetSpeed: Int = 0,
		var targetDir: Vector? = null,
		var accel: Double = 0.0
	) {
		var lastBlockCount = starship.blockCount

		fun accelerate(maxSpeed: Int, thrusterPower: Double) {
			val dir = this.targetDir ?: Vector()
			val speed = if (maxSpeed <= 0) targetSpeed else min(targetSpeed, maxSpeed)
			val newVelocity = dir.clone().multiply(speed)
			newVelocity.y = 0.0
			moveTowards(velocity, newVelocity, getRealAccel(thrusterPower) * SECONDS_PER_CRUISE)
			velocity.y = 0.0
			if (velocity.x.isNaN()) velocity.x = 0.0
			if (velocity.z.isNaN()) velocity.z = 0.0
		}

		// multiplied by power percent and rounded to the nearest hundredth
		fun getRealAccel(thrusterPower: Double): Double = (accel * thrusterPower).roundToHundredth()

		private fun moveTowards(vector: Vector, other: Vector, maxDistance: Double): Vector {
			val direction = other.clone().subtract(vector).normalize()
			val distance = min(maxDistance, other.distance(vector))
			if (distance < maxDistance) {
				vector.x = other.x
				vector.y = other.y
				vector.z = other.z
				return vector
			}
			return vector.add(direction.multiply(distance))
		}
	}

	override fun onEnable() {
		Tasks.syncRepeat(0L, (20 * SECONDS_PER_CRUISE).toLong()) {
			for (starship in ActiveStarships.allPlayerShips()) {
				if (!PilotedStarships.isPiloted(starship)) {
					continue
				}

				val player = starship.requirePilot()

				if (shouldStopCruising(starship)) {
					stopCruising(player, starship)
				}

				updateCruisingShip(starship)
			}
		}
	}

	private fun updateCruisingShip(starship: ActivePlayerStarship) {
		processUpdatedHullIntegrity(starship)

		val oldVelocity = starship.cruiseData.velocity.clone()

		starship.cruiseData.accelerate(starship.speedLimit, starship.reactor.powerDistributor.thrusterPortion)
		val velocity = starship.cruiseData.velocity
		val speed = velocity.length()

		if (oldVelocity.distance(velocity) > 0.01) {
			// velocity has changed
			val targetSpeed = starship.cruiseData.targetSpeed
			starship.sendActionBar("&bCruise Speed: &b${speed.roundToHundredth()}&8/&3$targetSpeed")

			if (starship.isInterdicting) {
				starship.setIsInterdicting(false)
			}
		}

		// immobile
		if (speed * SECONDS_PER_CRUISE < 1) {
			return
		}

		val dx = (velocity.x * SECONDS_PER_CRUISE).toInt()
		val dy = (velocity.y * SECONDS_PER_CRUISE).toInt()
		val dz = (velocity.z * SECONDS_PER_CRUISE).toInt()

		if (StarshipControl.locationCheck(starship, dx, dy, dz)) {
			return
		}

		if (starship.isTeleporting) {
			return
		}

		TranslateMovement.loadChunksAndMove(starship, dx, dy, dz)
	}

	private fun processUpdatedHullIntegrity(starship: ActivePlayerStarship) {
		val oldBlockCount = starship.cruiseData.lastBlockCount
		val newBlockCount = starship.blockCount

		if (oldBlockCount == newBlockCount) {
			return
		}

		starship.generateThrusterMap()
	}

	private fun shouldStopCruising(starship: ActivePlayerStarship): Boolean {
		if (starship.isDirectControlEnabled) {
			return true
		}

//		if (Hyperspace.isWarmingUp(starship)) {
//			return true
//		}

		return false
	}

	fun startCruising(player: Player, starship: ActivePlayerStarship) {
		if (!StarshipStartCruisingEvent(starship, player).callEvent()) {
			return
		}

		val dir = player.location.direction.setY(0).normalize()

		val dx = if (abs(dir.x) >= 0.5) sign(dir.x).toInt() else 0
		val dz = if (abs(dir.z) > 0.5) sign(dir.z).toInt() else 0

		if (dx == 0 && dz == 0) {
			player action "&cCan't go up or down"
			return
		}

		// ThrustData is a binomial data class so we can just expand it like this
		var (accel, maxSpeed) = starship.getThrustData(dx, dz)
		if (maxSpeed == 0) {
			player msg "&cCan't cruise in that direction"
			return
		}

		if (!SpaceWorlds.contains(starship.world)) {
			maxSpeed /= 2
		}

		starship.cruiseData.accel = accel
		starship.cruiseData.targetSpeed = maxSpeed
		starship.cruiseData.targetDir = Vector(dx, 0, dz).normalize()

		val realAccel = starship.cruiseData.getRealAccel(starship.reactor.powerDistributor.thrusterPortion)
		val info = "&b$dx,$dz &8; &eAccel&8/&aSpeed&8: &e$realAccel&8/&a$maxSpeed"
		if (!isCruising(starship)) {
			starship.sendMessage("&3Cruise started, dir&8: $info")
			updateCruisingShip(starship)
		} else {
			starship.sendActionBar("&3Adjusted dir to $info &e&o[Left click to stop]")
		}
	}

	fun stopCruising(player: Player, starship: ActivePlayerStarship) {
		if (!StarshipStopCruisingEvent(starship, player).callEvent()) {
			return
		}

		if (!isCruising(starship)) {
			if (starship.cruiseData.velocity.lengthSquared() != 0.0) {
				player action "&cStarship is decelerating"
			} else {
				player action "&cStarship is not cruising"
			}
			return
		}

		starship.cruiseData.targetDir = null
		starship.sendMessage("&cCruise stopped, decelerating...")
	}

	fun forceStopCruising(starship: ActivePlayerStarship) {
		starship.cruiseData = CruiseData(starship)
	}

	fun isCruising(starship: ActivePlayerStarship) = starship.cruiseData.targetDir != null
}
