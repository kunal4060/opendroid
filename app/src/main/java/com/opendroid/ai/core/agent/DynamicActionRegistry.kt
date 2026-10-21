package com.opendroid.ai.core.agent

import com.opendroid.ai.actions.base.Action
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime overlay of dynamically-registered actions (today: MCP tools from
 * D5; the mechanism is generic) on top of the static [ActionSchema].
 *
 * The schema and approval policy are process-wide `object`s consulted from
 * many call sites, so the registry is one too: [ActionSchema]'s lookups,
 * prompt builders and `isNeverAutoApprove` read built-ins ∪ this registry,
 * and `ActionDispatcher` resolves handlers here when its static map misses.
 * The Hilt-managed producers (the MCP tool bridge) own the lifecycle —
 * registering a server's tools when it connects and withdrawing them the
 * moment it disconnects, is disabled, or is deleted, so a vanished tool
 * can never linger in the planner catalogue.
 */
object DynamicActionRegistry {

    private val definitions = ConcurrentHashMap<String, ActionDefinition>()
    private val handlers = ConcurrentHashMap<String, Action>()
    private val namesByOwner = ConcurrentHashMap<String, MutableSet<String>>()

    // Audit-2 #20: action name → the owner that currently owns it. The
    // name-keyed maps alone couldn't tell whose registration a name was, so
    // a second owner registering the same tool name silently overwrote the
    // first owner's handler — and when EITHER owner later withdrew, the
    // name was deleted outright, unregistering the still-connected owner's
    // tool mid-conversation.
    private val ownerByName = ConcurrentHashMap<String, String>()

    private val _revision = MutableStateFlow(0)

    /** Bumps on every mutation; observers (prompts, Settings) can react. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** All currently-registered dynamic definitions. */
    fun allDefinitions(): List<ActionDefinition> = definitions.values.sortedBy { it.name }

    fun definitionFor(actionName: String): ActionDefinition? = definitions[actionName]

    fun handlerFor(actionName: String): Action? = handlers[actionName]

    fun contains(actionName: String): Boolean = definitions.containsKey(actionName)

    fun actionNames(): Set<String> = definitions.keys.toSet()

    /**
     * Atomically replaces everything [owner] had registered (a server's
     * whole tool set) with [entries] of action name → (definition, handler).
     *
     * Collision rule (audit-2 #20): a name currently owned by a DIFFERENT
     * owner is never overwritten — that entry is rejected (skipped) and the
     * incumbent owner keeps the name until it withdraws. First registrant
     * wins, deterministically, instead of last-writer-wins corruption.
     */
    fun replaceForOwner(owner: String, entries: Map<String, Pair<ActionDefinition, Action>>) {
        synchronized(this) {
            val removed = removeForOwnerLocked(owner)
            if (entries.isEmpty()) {
                if (removed) _revision.value += 1
                return
            }
            val names = mutableSetOf<String>()
            entries.forEach { (actionName, entry) ->
                val currentOwner = ownerByName[actionName]
                if (currentOwner != null && currentOwner != owner) {
                    // Another owner holds this name — reject the collision.
                    return@forEach
                }
                definitions[actionName] = entry.first
                handlers[actionName] = entry.second
                ownerByName[actionName] = owner
                names += actionName
            }
            if (names.isEmpty()) {
                namesByOwner.remove(owner)
            } else {
                namesByOwner[owner] = names
            }
            _revision.value += 1
        }
    }

    /** Withdraws everything [owner] registered (server off / deleted / unreachable). */
    fun removeForOwner(owner: String) {
        synchronized(this) {
            if (removeForOwnerLocked(owner)) _revision.value += 1
        }
    }

    /** Replaces one definition in place (e.g. an approval-grant flag flip). */
    fun updateDefinition(definition: ActionDefinition) {
        synchronized(this) {
            if (definitions.containsKey(definition.name)) {
                definitions[definition.name] = definition
                _revision.value += 1
            }
        }
    }

    fun clear() {
        synchronized(this) {
            definitions.clear()
            handlers.clear()
            namesByOwner.clear()
            ownerByName.clear()
            _revision.value += 1
        }
    }

    private fun removeForOwnerLocked(owner: String): Boolean {
        val names = namesByOwner.remove(owner) ?: return false
        var removedAny = false
        names.forEach { name ->
            // Audit-2 #20: remove only names this owner actually owns —
            // a name another owner holds (collision survivor) must stay
            // registered for its real owner.
            if (ownerByName[name] == owner) {
                ownerByName.remove(name)
                definitions.remove(name)
                handlers.remove(name)
                removedAny = true
            }
        }
        return removedAny
    }
}
