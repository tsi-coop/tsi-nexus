from nexus_agent import DomainProfile, TestPrompt


PROFILES = {
    "microfinance": DomainProfile(
        name="microfinance",
        seed_guide="docs/seed/microfinance.md",
        target_type="member",
        prompts=[
            TestPrompt("Show the repayment and risk profile for {target}", "REVIEW", "/review {target}"),
            TestPrompt("Can we disburse a loan for {target}?", "DISBURSE_LOAN", "/disburse {target} 50000"),
            TestPrompt("Record a weekly repayment for {target}", "RECORD_REPAYMENT", "/collect {target} 3500"),
            TestPrompt("Verify collateral for {target}", "VERIFY_COLLATERAL_ASSET", "/verify_asset {target}"),
        ],
    ),
    "healthcare": DomainProfile(
        name="healthcare",
        seed_guide="docs/seed/healthcare-clinic.md",
        target_type="patient",
        prompts=[
            TestPrompt("Show the current clinical context for {target}", "REVIEW", "/review {target}"),
            TestPrompt("Register a new encounter for {target}", "REGISTER_ENCOUNTER", "/admit {target}"),
            TestPrompt("Place a lab order for {target}", "PLACE_LAB_ORDER", "/lab_order {target}"),
            TestPrompt("Refer {target} to a specialist", "SPECIALIST_REFERRAL", "/refer {target}"),
        ],
    ),
    "manufacturing": DomainProfile(
        name="manufacturing",
        seed_guide="docs/seed/manufacturing.md",
        target_type="work_order",
        prompts=[
            TestPrompt("Show the production status for {target}", "REVIEW", "/review {target}"),
            TestPrompt("Start the job for {target}", "START_WORK_ORDER", "/start_job {target}"),
            TestPrompt("Record material consumption for {target}", "RECORD_MATERIAL_USE", "/log_material {target}"),
            TestPrompt("Complete the work order for {target}", "COMPLETE_WORK_ORDER", "/complete_order {target}"),
        ],
    ),
    "edtech": DomainProfile(
        name="edtech",
        seed_guide="docs/seed/edtech.md",
        target_type="learner",
        prompts=[
            TestPrompt("Show progress and assessment history for {target}", "REVIEW", "/review {target}"),
            TestPrompt("Enroll {target} into a cohort", "ENROLL_LEARNER", "/enroll {target}"),
            TestPrompt("Record an assessment score for {target}", "RECORD_ASSESSMENT_SCORE", "/grade {target}"),
            TestPrompt("Issue a credential for {target}", "ISSUE_CREDENTIAL", "/certify {target}"),
        ],
    ),
    "services": DomainProfile(
        name="services",
        seed_guide="docs/seed/services.md",
        target_type="project",
        prompts=[
            TestPrompt("Show project health and billing context for {target}", "REVIEW", "/review {target}"),
            TestPrompt("Log billable time against {target}", "RECORD_TIME_ENTRY", "/log_time {target}"),
            TestPrompt("Approve a milestone for {target}", "APPROVE_MILESTONE", "/approve_milestone {target}"),
            TestPrompt("Raise an invoice for {target}", "GENERATE_INVOICE", "/raise_invoice {target}"),
        ],
    ),
    "hr_services": DomainProfile(
        name="hr_services",
        seed_guide="docs/seed/hr-services.md",
        target_type="candidate",
        prompts=[
            TestPrompt("Show candidate fit and compliance context for {target}", "REVIEW", "/review {target}"),
            TestPrompt("Run assessment for {target}", "TRIGGER_HR_TECH_ASSESSMENT", "/run_assessment {target}"),
            TestPrompt("Verify compliance for {target}", "VERIFY_BGV_STATUTORY_DOCS", "/verify_compliance {target}"),
            TestPrompt("Generate a placement offer for {target}", "ISSUE_PLACEMENT_OFFER", "/generate_offer {target}"),
        ],
    ),
}

