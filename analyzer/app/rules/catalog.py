"""Rule catalog and product taxonomy.

`lm_rules.csv` holds every rule the engine knows; `categories.json` holds the product categories with
the tags rules select on. A rule's `applies_to` is a space-separated list of selectors:

    all            every category
    tag:food       categories carrying the tag
    cat:edible_oil one category
    group:food     every category in the group
    -tag:drug      excludes (a leading minus negates any selector)

A rule applies when at least one positive selector matches (or there are none) and no negative one
does. `scope` decides which packages it applies to: "retail" rules are the Chapter II declarations
(subject to the Rule 3 scope and Rule 26 exemptions), "wholesale" rules are Rule 24, and "always"
rules apply to every package.
"""

from __future__ import annotations

import csv
import json
import os
import re
from dataclasses import dataclass, field
from functools import lru_cache

_DIR = os.path.dirname(__file__)
RULES_CSV = os.path.join(_DIR, "lm_rules.csv")
CATEGORIES_JSON = os.path.join(_DIR, "categories.json")


@dataclass(frozen=True)
class Category:
    id: str
    name: str
    group: str
    tags: frozenset
    strong: tuple
    weak: tuple
    legacy: tuple
    dims: tuple
    volume_needs_mass: bool
    dim_overrides: tuple
    quantity_ref: str
    laws: tuple
    standards: dict

    def has(self, tag: str) -> bool:
        return tag in self.tags

    def dims_for(self, text: str) -> tuple[tuple, str]:
        """Allowed measures for this commodity, honouring keyword overrides ('curd' in dairy -> mass)."""
        for o in self.dim_overrides:
            if re.search(o["match"], text or "", re.I):
                return tuple(o["dims"]), o.get("ref", self.quantity_ref)
        return self.dims, self.quantity_ref

    def summary(self) -> dict:
        return {"id": self.id, "name": self.name, "group": self.group}


@dataclass(frozen=True)
class Rule:
    id: str
    sort_order: int
    section: str
    title: str
    reference: str
    severity: str
    validator: str
    applies_to: tuple
    scope: str
    params: dict = field(default_factory=dict, hash=False, compare=False)
    description: str = ""

    @property
    def category(self) -> str:  # the report / database call the rule's section its "category"
        return self.section

    def selects(self, cat: Category) -> bool:
        positives = [s for s in self.applies_to if not s.startswith("-")]
        negatives = [s[1:] for s in self.applies_to if s.startswith("-")]
        if any(_match(s, cat) for s in negatives):
            return False
        return not positives or any(_match(s, cat) for s in positives)


def _match(selector: str, cat: Category) -> bool:
    if selector == "all":
        return True
    kind, _, value = selector.partition(":")
    if kind == "tag":
        return value in cat.tags
    if kind == "cat":
        return value == cat.id
    if kind == "group":
        return value == cat.group
    raise ValueError(f"Unknown selector '{selector}'")


@dataclass(frozen=True)
class Taxonomy:
    version: str
    categories: tuple
    groups: dict
    package_types: tuple
    tag_glossary: dict

    @property
    def by_id(self) -> dict:
        return {c.id: c for c in self.categories}

    def get(self, cid: str | None) -> Category | None:
        return self.by_id.get((cid or "").strip().lower())

    @property
    def general(self) -> Category:
        return self.by_id["general"]


@lru_cache(maxsize=4)
def load_taxonomy(path: str = CATEGORIES_JSON) -> Taxonomy:
    with open(path, encoding="utf-8") as fh:
        raw = json.load(fh)
    cats = []
    for c in raw["categories"]:
        q = c.get("quantity") or {}
        cats.append(Category(
            id=c["id"], name=c["name"], group=c["group"], tags=frozenset(c.get("tags") or []),
            strong=tuple(k.lower() for k in (c.get("keywords") or {}).get("strong", [])),
            weak=tuple(k.lower() for k in (c.get("keywords") or {}).get("weak", [])),
            legacy=tuple(x.lower() for x in c.get("legacy") or []),
            dims=tuple(q.get("dims") or ["mass", "volume", "number", "length", "area"]),
            volume_needs_mass=bool(q.get("volume_needs_mass")),
            dim_overrides=tuple(q.get("overrides") or []), quantity_ref=q.get("ref", ""),
            laws=tuple(c.get("laws") or []), standards=c.get("standards") or {},
        ))
    ids = [c.id for c in cats]
    if len(ids) != len(set(ids)) or "general" not in ids:
        raise ValueError("categories.json needs unique ids and a 'general' fallback")
    return Taxonomy(raw.get("version", ""), tuple(cats), {g["id"]: g["name"] for g in raw.get("groups", [])},
                    tuple(raw.get("package_types") or []), raw.get("tag_glossary") or {})


def load_rules(path: str = RULES_CSV, validators: dict | None = None) -> list[Rule]:
    with open(path, encoding="utf-8", newline="") as fh:
        rows = list(csv.DictReader(fh))
    rules = []
    for r in rows:
        rules.append(Rule(
            id=r["id"], sort_order=int(r["sort_order"]), section=r["section"], title=r["title"], reference=r["reference"],
            severity=r["severity"], validator=r["validator"], applies_to=tuple((r.get("applies_to") or "all").split()),
            scope=(r.get("scope") or "retail").strip(), params=json.loads(r["params"]) if r.get("params") else {},
            description=r.get("description", ""),
        ))
    ids = [r.id for r in rules]
    if len(ids) != len(set(ids)):
        raise ValueError("Duplicate rule ids in the catalog")
    bad_scope = [r.id for r in rules if r.scope not in ("retail", "wholesale", "always")]
    if bad_scope:
        raise ValueError(f"Unknown rule scope for {bad_scope}")
    if validators is not None:
        missing = sorted({r.validator for r in rules if r.validator not in validators})
        if missing:
            raise ValueError(f"Rule catalog references unknown validators: {missing}")
    # Fails loudly on a typo in a selector
    general = load_taxonomy().general
    for r in rules:
        r.selects(general)
    return sorted(rules, key=lambda r: r.sort_order)


def select_rules(rules: list[Rule], category: Category, package_type: str) -> tuple[list[Rule], list[Rule]]:
    """(rules evaluated for this package, catalog rules that do not concern it)."""
    chosen, skipped = [], []
    for r in rules:
        scope_ok = r.scope == "always" or (r.scope == "wholesale") == (package_type == "wholesale")
        (chosen if scope_ok and r.selects(category) else skipped).append(r)
    return chosen, skipped


# ---------------------------------------------------------------------------
# Regulator scoping: which product categories fall under FSSAI / BIS
# ---------------------------------------------------------------------------
AUTHORITIES = ("fssai", "bis")
_FSSAI_LAW = re.compile(r"(?<![A-Za-z])FSS(?![A-Za-z])|Food Safety", re.I)
_BIS_LAW = re.compile(r"(?<![A-Za-z])BIS(?![A-Za-z])|(?<![A-Za-z])IS [0-9]|Quality Control|Compulsory Registration", re.I)
# Rule-id prefixes that belong to one regulator only; the common label-declaration rules apply to both
_AUTHORITY_ONLY = {"fssai": ("FS-",), "bis": ("BI-",)}


def categories_for(authority: str, taxonomy: "Taxonomy") -> set[str]:
    """Ids of the categories whose governing laws include the regulator's."""
    pattern = _FSSAI_LAW if authority == "fssai" else _BIS_LAW
    return {cid for cid, c in taxonomy.by_id.items() if any(pattern.search(law) for law in c.laws)}


def rules_for_authority(rules: list, authority: str) -> list:
    """Drops the other regulator's dedicated rules (FSSAI scan skips BIS marks and vice versa)."""
    other = "bis" if authority == "fssai" else "fssai"
    banned = _AUTHORITY_ONLY[other]
    return [r for r in rules if not r.id.startswith(banned)]
