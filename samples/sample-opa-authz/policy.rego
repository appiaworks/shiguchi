# Gateway authorization policy.
#
# Load with:  opa run --server policy.rego data.json
# The gateway queries /v1/data/gateway/authz/allow, which is this package + the `allow` rule.

package gateway.authz

import rego.v1

# Deny by default. `allow` being undefined is also a deny on the gateway side, so a typo in the
# package or rule name fails closed rather than opening the gateway.
default allow := false

allow if {
	input.subject.authenticated
	every_scope_held
	every_authz_rule_satisfied
}

# ---------------------------------------------------------------------------
# Scope check
#
# The gateway already enforces `auth_scopes` before calling OPA, so this is redundant today.
# It is here because a policy should be able to stand on its own: if someone points a different
# gateway (or a service mesh sidecar) at this same policy, the scope requirement travels with it.
# ---------------------------------------------------------------------------

every_scope_held if {
	every scope in input.required_scopes {
		held_scopes[scope]
	}
}

# Spring Security prefixes JWT scopes with "SCOPE_"; strip it so the policy compares raw names.
held_scopes[scope] if {
	some authority in input.subject.authorities
	scope := trim_prefix(authority, "SCOPE_")
}

# ---------------------------------------------------------------------------
# Object-level rules, from the (shiguchi.api.method).authz proto annotation
#
# Each entry carries object_type, relation, id_field, and the gateway's resolved `id`
# (null when the request did not supply that field).
# ---------------------------------------------------------------------------

every_authz_rule_satisfied if {
	every rule in input.resource.authz {
		rule_satisfied(rule)
	}
}

# A rule whose id could not be resolved is NEVER satisfied. Written explicitly rather than relying
# on the lookup below failing, so the intent survives future edits: an unresolvable rule is a
# failure to authorize, not an absent constraint.
rule_satisfied(rule) if {
	rule.id != null
	relation_holds(rule)
}

# Direct grant: the subject appears in the object's list for that relation.
relation_holds(rule) if {
	object_key := sprintf("%s:%s", [rule.object_type, rule.id])
	data.grants[object_key][rule.relation][_] == input.subject.id
}

# Group grant: a group the subject belongs to holds the relation.
relation_holds(rule) if {
	object_key := sprintf("%s:%s", [rule.object_type, rule.id])
	some group in input.subject.claims.groups
	data.grants[object_key][rule.relation][_] == sprintf("group:%s", [group])
}

# Ownership implies read, mirroring the OpenFGA sample's `viewer: ... or owner`.
relation_holds(rule) if {
	rule.relation == "viewer"
	object_key := sprintf("%s:%s", [rule.object_type, rule.id])
	data.grants[object_key].owner[_] == input.subject.id
}
