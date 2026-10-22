"""Single-feature statistical reference, not a malware classifier or Android implementation."""
from dataclasses import dataclass
from statistics import median
from math import isfinite, log1p

@dataclass(frozen=True)
class Result:
    anomaly_score: float | None
    risk_score: float | None
    data_confidence: float
    reason: str
    training_eligible: bool
    malware_probability: None = None

def assess_upload(current_bytes, history_bytes, *, completeness=1.0, known_ioc=False,
                  minimum_history=30, scale_floor=0.15):
    if not isfinite(completeness) or not 0 <= completeness <= 1:
        raise ValueError('completeness must be finite in [0,1]')
    if not isinstance(minimum_history, int) or minimum_history < 1:
        raise ValueError('minimum_history must be positive')
    if not isfinite(scale_floor) or scale_floor <= 0:
        raise ValueError('scale_floor must be finite and positive')
    h = list(history_bytes)
    if any(not isfinite(x) or x < 0 for x in h):
        raise ValueError('history must contain finite nonnegative values')
    if current_bytes is not None and (not isfinite(current_bytes) or current_bytes < 0):
        raise ValueError('current must be finite and nonnegative')
    confidence = min(1.0, len(h)/minimum_history) * completeness
    if current_bytes is None:
        return Result(None, 90.0 if known_ioc else None, 0.0, 'MISSING_FEATURE', False)
    if len(h) < minimum_history:
        return Result(None, 90.0 if known_ioc else None, confidence,
                      'INSUFFICIENT_HISTORY', False)
    if completeness < 0.9:
        return Result(None, 90.0 if known_ioc else None, confidence,
                      'INCOMPLETE_WINDOW', False)
    values = [log1p(x) for x in h]
    center = median(values)
    mad = median(abs(x-center) for x in values)
    # One-sided excess upload detector. Scores are illustrative ordinal heuristics.
    z = max(0.0, (log1p(current_bytes)-center) / max(1.4826*mad, scale_floor))
    score = min(100.0, z*10.0)
    risk = max(90.0 if known_ioc else 0.0, score*0.5)
    return Result(score, risk, confidence, 'UPLOAD_DEVIATION' if score >= 60 else 'WITHIN_BASELINE',
                  score < 60 and not known_ioc)

def ewma_next(previous, observation, alpha=0.2):
    if not isfinite(alpha) or not 0 < alpha <= 1:
        raise ValueError('alpha must be in (0,1]')
    if not isfinite(observation) or (previous is not None and not isfinite(previous)):
        raise ValueError('finite observations required')
    return observation if previous is None else alpha*observation+(1-alpha)*previous

def delta_counter(previous, current, previous_boot, current_boot):
    if current < 0 or (previous is not None and previous < 0):
        raise ValueError('negative counter')
    if previous is None or previous_boot != current_boot or current < previous:
        return None  # reset/unavailable, not zero traffic and not a huge spike
    return current-previous
