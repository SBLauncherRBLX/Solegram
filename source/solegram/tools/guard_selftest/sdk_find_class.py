"""Stands in for the SDK's hook_utils.find_class in the self-test (the SDK asks the guard, then looks the class up)."""
import solegram_guard


def find_class(name):
    solegram_guard.check_java_class(name)
    return "Class<%s>" % name
