#!/usr/bin/env python3
import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass


@dataclass(frozen=True)
class TestPrompt:
    text: str
    action_type: str
    command: str


@dataclass(frozen=True)
class DomainProfile:
    name: str
    seed_guide: str
    target_type: str
    prompts: list[TestPrompt]


class NexusClient:
    def __init__(self, base_url, api_key, api_secret):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.api_secret = api_secret

    def get(self, path):
        return self._request("GET", path)

    def post(self, path, payload):
        return self._request("POST", path, payload)

    def _request(self, method, path, payload=None):
        body = None
        headers = {
            "X-API-Key": self.api_key,
            "X-API-Secret": self.api_secret,
        }
        if payload is not None:
            body = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"

        req = urllib.request.Request(
            self.base_url + path,
            data=body,
            headers=headers,
            method=method,
        )

        try:
            with urllib.request.urlopen(req, timeout=30) as res:
                raw = res.read().decode("utf-8")
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode("utf-8")
            try:
                parsed = json.loads(raw)
            except json.JSONDecodeError:
                parsed = {"message": raw}
            return {
                "success": False,
                "status": exc.code,
                "error": parsed,
            }


class DomainAgent:
    def __init__(self, client, profile):
        self.client = client
        self.profile = profile

    def run(self):
        print(f"TSI Nexus domain test agent: {self.profile.name}")
        print(f"Seed guide: {self.profile.seed_guide}")

        entities = self.client.get("/api/entities")
        self._require_success("GET /api/entities", entities)

        graph = self.client.get("/api/graph")
        self._print_status("GET /api/graph", graph)

        target = self._choose_target(entities)
        print(f"Target entity: {target}")

        context = self.client.post("/api/context", {"external_id": target})
        self._print_status("POST /api/context", context)

        capture_path = "/api/capture?" + urllib.parse.urlencode({"external_id": target})
        capture = self.client.get(capture_path)
        self._print_status("GET /api/capture", capture)
        self._print_capture_summary(capture)

        for prompt in self.profile.prompts:
            self._run_prompt(target, prompt)

    def _run_prompt(self, target, prompt):
        prompt_text = prompt.text.format(target=target)
        command = prompt.command.format(target=target)

        print("")
        print(f"Prompt: {prompt_text}")

        intent = self.client.post("/api/intent", {"intent": prompt_text})
        self._print_status("POST /api/intent", intent)
        self._print_components(intent)

        governance = self.client.post(
            "/api/governance",
            {
                "action_type": prompt.action_type,
                "intent_raw": command,
                "params": {"target_external_id": target},
            },
        )
        self._print_status(f"POST /api/governance [{prompt.action_type}]", governance)
        if not governance.get("success"):
            reason = governance.get("reason") or governance.get("message") or governance.get("error")
            print(f"  governance reason: {reason}")

    def _choose_target(self, entities):
        for entity_type in entities.get("entity_types", []):
            if entity_type.get("type") != self.profile.target_type:
                continue
            sample = entity_type.get("sample") or []
            if sample:
                return sample[0].get("handle")

        available = [
            item.get("type")
            for item in entities.get("entity_types", [])
            if item.get("sample")
        ]
        raise RuntimeError(
            f"No sample entity found for type '{self.profile.target_type}'. "
            f"Available populated types: {', '.join(available) or 'none'}"
        )

    def _print_capture_summary(self, capture):
        schemas = capture.get("schemas") or []
        if not schemas:
            print("  capture schemas: none returned")
            return
        labels = []
        for schema in schemas:
            labels.append(f"{schema.get('schema_id')}:{schema.get('action_type')}")
        print("  capture schemas: " + ", ".join(labels))

    def _print_components(self, intent):
        components = intent.get("components") or []
        if not components:
            return
        labels = [component.get("component_type", "unknown") for component in components]
        print("  components: " + ", ".join(labels))

    def _print_status(self, label, response):
        status = "PASS" if response.get("success") else "CHECK"
        print(f"{status} {label}")

    def _require_success(self, label, response):
        if response.get("success"):
            print(f"PASS {label}")
            return
        raise RuntimeError(f"{label} failed: {json.dumps(response, indent=2)}")


def build_client():
    base_url = os.getenv("NEXUS_BASE_URL", "http://localhost:8084")
    api_key = os.getenv("NEXUS_API_KEY")
    api_secret = os.getenv("NEXUS_API_SECRET")

    missing = [
        name
        for name, value in {
            "NEXUS_API_KEY": api_key,
            "NEXUS_API_SECRET": api_secret,
        }.items()
        if not value
    ]
    if missing:
        raise RuntimeError("Missing environment variables: " + ", ".join(missing))

    return NexusClient(base_url, api_key, api_secret)


def run_profile(profile):
    agent = DomainAgent(build_client(), profile)
    agent.run()


def main(argv=None):
    from domain_profiles import PROFILES

    parser = argparse.ArgumentParser(description="Run a TSI Nexus domain test agent.")
    parser.add_argument("domain", choices=sorted(PROFILES))
    args = parser.parse_args(argv)
    run_profile(PROFILES[args.domain])


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(1)

