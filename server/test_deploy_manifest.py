from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parent.parent
MANIFEST = PROJECT_ROOT / "bin" / "deploy.manifest"


def _manifest_entries():
    entries = []
    for line in MANIFEST.read_text().splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        action, target, *_ = stripped.split()
        entries.append((action, target))
    return entries


def test_manifest_references_existing_shipped_paths():
    entries = _manifest_entries()

    assert ("ship-dir", "server") in entries
    assert ("ship-dir", "mcp_servers") in entries
    assert ("ship-bin", "server.sh") in entries

    for action, target in entries:
        if action == "ship-dir":
            assert (PROJECT_ROOT / target).is_dir(), target
        elif action == "ship-file":
            assert (PROJECT_ROOT / target).is_file(), target
        elif action == "ship-bin":
            script = PROJECT_ROOT / "bin" / target
            assert script.is_file(), target
            assert script.stat().st_mode & 0o111, target


def test_manifest_keeps_android_and_local_state_out_of_server_deploy():
    entries = set(_manifest_entries())

    assert ("exclude", "app") in entries
    assert ("exclude", "core") in entries
    assert ("exclude", "feature") in entries
    assert ("exclude", "testdata") in entries
    assert ("exclude-bin", "deploy-prod.sh") in entries
    assert ("exclude-bin", "deploy.manifest") in entries

    forbidden_ships = {
        ("ship-dir", "app"),
        ("ship-dir", "core"),
        ("ship-dir", "feature"),
        ("ship-dir", "server/data"),
        ("ship-dir", "server/.venv"),
    }
    assert entries.isdisjoint(forbidden_ships)


def test_manifest_classifies_top_level_entries_and_bin_scripts():
    entries = set(_manifest_entries())
    classified_top_level = {
        target
        for action, target in entries
        if action in {"ship-dir", "ship-file", "exclude"}
    }
    ignored_top_level = {".git", "bin"}

    actual_top_level = {
        path.name
        for path in PROJECT_ROOT.iterdir()
        if path.name not in ignored_top_level
    }
    assert actual_top_level <= classified_top_level

    classified_bin = {
        target
        for action, target in entries
        if action in {"ship-bin", "exclude-bin"}
    }
    actual_bin = {path.name for path in (PROJECT_ROOT / "bin").iterdir()}
    assert actual_bin <= classified_bin
