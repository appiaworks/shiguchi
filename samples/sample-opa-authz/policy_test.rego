# Tests for policy.rego.
#
# Run with:  opa test policy.rego policy_test.rego data.json
#
# These pin the behaviours the sample README promises. The two that matter most are
# `test_null_id_denies` and `test_viewer_grant_does_not_satisfy_owner` — both are the shapes where
# a plausible-looking policy edit would quietly open the gateway.

package gateway.authz_test

import data.gateway.authz
import rego.v1

# Base input: alice, authenticated, holding the scope the route requires.
base_input := {
	"subject": {
		"id": "alice",
		"authenticated": true,
		"authorities": ["SCOPE_orders:read"],
		"claims": {"groups": []},
	},
	"action": {
		"service": "orders.OrderService",
		"method": "GetOrder",
		"http_method": "GET",
		"http_path": "/v1/orders/{orderId}",
		"graphql_field": null,
	},
	"resource": {
		"arguments": {"orderId": "42"},
		"authz": [{
			"object_type": "order",
			"relation": "viewer",
			"id_field": "order_id",
			"id": "42",
		}],
	},
	"required_scopes": ["orders:read"],
}

# alice owns order:42, and ownership implies viewer.
test_owner_may_view if {
	authz.allow with input as base_input
}

# bob holds no direct grant, but `auditors` is a viewer of order:42 and his token says he is a
# member. The membership is never stored in data.json — it arrives on the request.
test_group_member_may_view if {
	authz.allow with input as object.union(base_input, {"subject": {
		"id": "bob",
		"authenticated": true,
		"authorities": ["SCOPE_orders:read"],
		"claims": {"groups": ["auditors"]},
	}})
}

test_unrelated_subject_denied if {
	not authz.allow with input as object.union(base_input, {"subject": {
		"id": "carol",
		"authenticated": true,
		"authorities": ["SCOPE_orders:read"],
		"claims": {"groups": []},
	}})
}

# order:99 belongs to dave; alice's grant on order:42 must not carry over.
test_grant_does_not_leak_across_objects if {
	not authz.allow with input as object.union(base_input, {"resource": {
		"arguments": {"orderId": "99"},
		"authz": [{
			"object_type": "order",
			"relation": "viewer",
			"id_field": "order_id",
			"id": "99",
		}],
	}})
}

# SECURITY: a rule the gateway could not resolve arrives with id == null. It must never be read as
# an absent constraint.
test_null_id_denies if {
	not authz.allow with input as object.union(base_input, {"resource": {
		"arguments": {},
		"authz": [{
			"object_type": "order",
			"relation": "viewer",
			"id_field": "order_id",
			"id": null,
		}],
	}})
}

# SECURITY, sharper: the test above passes even without the `rule.id != null` guard, because a null
# id builds the key "order:null" which simply is not in the data. That makes it pass for the wrong
# reason. This one injects a grant literally keyed "order:null" — so the guard is the ONLY thing
# denying, and deleting it flips this test to a failure.
test_null_id_denies_even_when_a_null_key_exists if {
	not authz.allow with input as object.union(base_input, {"resource": {
		"arguments": {},
		"authz": [{
			"object_type": "order",
			"relation": "viewer",
			"id_field": "order_id",
			"id": null,
		}],
	}})
		with data.grants as {"order:null": {"viewer": ["alice"]}}
}

# SECURITY: `viewer: ... or owner` goes one way only. bob is a viewer of order:42 via his group,
# which must not satisfy a route requiring `owner` (the DELETE binding in the sample).
test_viewer_grant_does_not_satisfy_owner if {
	not authz.allow with input as object.union(base_input, {
		"subject": {
			"id": "bob",
			"authenticated": true,
			"authorities": ["SCOPE_orders:read"],
			"claims": {"groups": ["auditors"]},
		},
		"resource": {
			"arguments": {"orderId": "42"},
			"authz": [{
				"object_type": "order",
				"relation": "owner",
				"id_field": "order_id",
				"id": "42",
			}],
		},
	})
}

test_missing_scope_denies if {
	not authz.allow with input as object.union(base_input, {"subject": {
		"id": "alice",
		"authenticated": true,
		"authorities": ["SCOPE_something-else"],
		"claims": {"groups": []},
	}})
}

test_unauthenticated_denies if {
	not authz.allow with input as object.union(base_input, {"subject": {
		"id": null,
		"authenticated": false,
		"authorities": [],
		"claims": {},
	}})
}

# A route with no authz rules still requires authentication and scopes, but imposes no object
# constraint — the gateway's scope-only behaviour.
test_no_authz_rules_allows_authenticated_caller if {
	authz.allow with input as object.union(base_input, {"resource": {
		"arguments": {},
		"authz": [],
	}})
}
