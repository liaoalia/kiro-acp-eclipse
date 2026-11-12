"""Prints one GitHub Actions error annotation per failed test in the surefire reports.

Run from the repository root after a failed build, so the cause is visible on
the run's page without downloading the reports.
"""
import glob
import xml.etree.ElementTree as ET

count = 0
for report in sorted(glob.glob("tests/*/target/surefire-reports/TEST-*.xml")):
    for case in ET.parse(report).getroot().iter("testcase"):
        for kind in ("failure", "error"):
            for problem in case.findall(kind):
                count += 1
                text = ((problem.get("message") or "") + " | " + (problem.text or ""))[:900]
                # Workflow commands are one line; these are their escapes.
                text = text.replace("%", "%25").replace("\r", "").replace("\n", "%0A")
                name = case.get("classname", "").split(".")[-1] + "." + (case.get("name") or "")
                print(f"::error title={name}::{text}")
print(f"{count} failed test(s)")
