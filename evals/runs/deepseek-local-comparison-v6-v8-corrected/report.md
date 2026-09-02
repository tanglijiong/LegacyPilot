# LegacyPilot Eval Comparison

- Dataset: `v0.3-core.1`
- Baseline: `deepseek-local-full-20260902-v6`
- Candidate: `deepseek-local-full-20260902-v8`
- Regression gate: **FAIL**

| Run | Pass@1 | Passed | Cost USD | p95 ms | Tokens |
| --- | ---: | ---: | ---: | ---: | ---: |
| Baseline | 45% | 9/20 | 0 | 26331 | 75380 |
| Candidate | 45% | 9/20 | 0 | 31941 | 93382 |

| Task | Baseline | Candidate | Primary failure | Delta cost | Delta ms |
| --- | --- | --- | --- | ---: | ---: |
| task-001 | PASSED | PASSED | NONE | 0 | 57 |
| task-002 | PASSED | PASSED | NONE | 0 | 3139 |
| task-003 | PASSED | PASSED | NONE | 0 | 1249 |
| task-004 | PASSED | PASSED | NONE | 0 | 569 |
| task-005 | FAILED | FAILED | ASSERTION_FAILURE | 0 | 1040 |
| task-006 | FAILED | FAILED | TEST_FAILURE | 0 | 2809 |
| task-007 | PASSED | PASSED | NONE | 0 | 1729 |
| task-008 | FAILED | FAILED | TEST_FAILURE | 0 | -8974 |
| task-009 | FAILED | FAILED | COMPILE_FAILURE | 0 | 7472 |
| task-010 | PASSED | PASSED | NONE | 0 | 1046 |
| task-011 | PASSED | PASSED | NONE | 0 | 895 |
| task-012 | PASSED | PASSED | NONE | 0 | 1100 |
| task-013 | FAILED | FAILED | COMPILE_FAILURE | 0 | -2198 |
| task-014 | FAILED | FAILED | TEST_FAILURE | 0 | -4901 |
| task-015 | FAILED | FAILED | ASSERTION_FAILURE | 0 | 2352 |
| task-016 | FAILED | FAILED | COMPILE_FAILURE | 0 | 16642 |
| task-017 | FAILED | FAILED | COMPILE_FAILURE | 0 | 2841 |
| task-018 | FAILED | FAILED | COMPILE_FAILURE | 0 | 1566 |
| task-019 | PASSED | PASSED | NONE | 0 | 2061 |
| task-020 | FAILED | FAILED | COMPILE_FAILURE | 0 | -3794 |

## Gate failures

- candidate pass@1 is below 70%
