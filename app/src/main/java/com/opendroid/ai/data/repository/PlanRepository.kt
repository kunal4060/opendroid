package com.opendroid.ai.data.repository

import com.opendroid.ai.data.db.dao.PlanDao
import com.opendroid.ai.data.db.entities.PlanEntity
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStatus
import com.opendroid.ai.data.models.PlanStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlanRepository @Inject constructor(
    private val planDao: PlanDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    val allPlans: Flow<List<Plan>> = planDao.getAllPlansFlow().map { entities ->
        entities.map { entity -> mapEntityToPlan(entity) }
    }

    suspend fun getPlanById(planId: String): Plan? {
        val entity = planDao.getPlanById(planId) ?: return null
        return mapEntityToPlan(entity)
    }

    suspend fun savePlan(plan: Plan) {
        val stepsJson = json.encodeToString(plan.steps)
        planDao.insertOrUpdatePlan(
            PlanEntity(
                planId = plan.planId,
                goal = plan.goal,
                estimatedDuration = plan.estimatedDuration,
                estimatedSteps = plan.estimatedSteps,
                stepsJson = stepsJson,
                status = plan.status.name,
                createdAt = plan.createdAt
            )
        )
    }

    suspend fun deletePlan(planId: String) {
        planDao.deletePlan(planId)
    }

    private fun mapEntityToPlan(entity: PlanEntity): Plan {
        val steps = try {
            json.decodeFromString<List<PlanStep>>(entity.stepsJson)
        } catch (e: Exception) {
            emptyList()
        }
        return Plan(
            planId = entity.planId,
            goal = entity.goal,
            estimatedDuration = entity.estimatedDuration,
            estimatedSteps = entity.estimatedSteps,
            steps = steps,
            // Tolerate status strings written by other builds (e.g. restored
            // from a hand-edited backup): an unknown value degrades to FAILED
            // instead of throwing on every allPlans emission (audit-4 #1).
            // BackupManager.validatePayload rejects such backups up front;
            // this is the defence in depth for rows already in the DB.
            status = runCatching { PlanStatus.valueOf(entity.status) }
                .getOrDefault(PlanStatus.FAILED),
            createdAt = entity.createdAt
        )
    }
}
