"""Static plugin metadata parser (reads top-level __id__, __name__... without executing the plugin)."""
import ast

_KEYS = {
    "__id__": "id",
    "__name__": "name",
    "__description__": "description",
    "__author__": "author",
    "__version__": "version",
    "__icon__": "icon",
    "__app_version__": "app_version",
    "__sdk_version__": "sdk_version",
    "__min_version__": "min_version",
    "__requirements__": "requirements",
}


def parse_source(source, filename="<plugin>"):
    tree = ast.parse(source, filename)
    result = {}
    for node in tree.body:
        targets = []
        if isinstance(node, ast.Assign):
            targets = [t for t in node.targets if isinstance(t, ast.Name)]
            value = node.value
        elif isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name) and node.value is not None:
            targets = [node.target]
            value = node.value
        else:
            continue
        for target in targets:
            key = _KEYS.get(target.id)
            if key is None:
                continue
            try:
                result[key] = ast.literal_eval(value)
            except Exception:
                pass
    reqs = result.get("requirements")
    if isinstance(reqs, str):
        reqs = [r.strip() for r in reqs.replace(",", "\n").splitlines() if r.strip()]
    elif isinstance(reqs, (list, tuple)):
        reqs = [str(r).strip() for r in reqs if str(r).strip()]
    else:
        reqs = []
    result["requirements"] = reqs
    if not result.get("app_version") and result.get("min_version"):
        result["app_version"] = ">=" + str(result["min_version"]).lstrip(">=")
    for k in ("id", "name", "description", "author", "version", "icon", "app_version", "sdk_version"):
        if k in result and result[k] is not None:
            result[k] = str(result[k])
    if not result.get("version"):
        result["version"] = "1.0"
    return result


def get_metadata(file_path):
    with open(file_path, "r", encoding="utf-8-sig") as f:
        return parse_source(f.read(), file_path)
