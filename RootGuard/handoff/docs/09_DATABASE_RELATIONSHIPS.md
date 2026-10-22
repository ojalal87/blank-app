# Key relationships
| Parent | Child | Cardinality / intent |
|---|---|---|
| app_installation | app_version | one to many observed versions |
| principal | principal_member | one to many package memberships |
| app_installation | principal_member | one to many time-bounded assignments |
| profile | policy_revision | one to many immutable versions |
| policy_revision | policy_rule | one to many rule snapshots |
| policy_revision | apply_transaction | one to many apply attempts |
| feature_window | anomaly_assessment | one per scorer version |
| model_snapshot | anomaly_assessment | many assessments reference prior model |
| incident | evidence / response_action / feedback | one to many |
| feed_source | feed_generation | many snapshots, at most one active |
| indicator | indicator_provenance | many generations/sources per value |
| feed_generation | indicator_provenance | many indicators in snapshot |

Cross-store principal/app/revision and indicator references have no SQL FK; preserve evidence snapshots and reconcile via repository/outbox. Principal IDs never mean package IDs. A package can change UID over time and a UID can represent multiple packages.
