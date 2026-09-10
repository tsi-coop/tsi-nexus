#!/usr/bin/env python3
from domain_profiles import PROFILES
from nexus_agent import run_profile


if __name__ == "__main__":
    run_profile(PROFILES["edtech"])

