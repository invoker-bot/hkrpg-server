"""Exercise the actual probe clock expression against the stopped first-failure H2 timestamps."""
import ast
import importlib.util
import inspect
from pathlib import Path
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / 'smoke-g61-first-talk.py'
SPEC = importlib.util.spec_from_file_location('g61_clock_projection_under_test', SCRIPT)
SMOKE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SMOKE)


def actual_probe_clock(anchor, day, wall):
    # Use the executable expression from the real probe call site, including any delegated helper.
    # A disconnected helper that passes unit tests cannot repair this fixture-ordering regression.
    tree = ast.parse(inspect.getsource(SMOKE.one))
    probe = next(node for node in ast.walk(tree)
                 if isinstance(node, ast.FunctionDef) and node.name == 'probe')
    assignments = [node for node in ast.walk(probe) if isinstance(node, ast.Assign)
                   and any(isinstance(name, ast.Name) and name.id == 'target' for name in node.targets)]
    if len(assignments) != 1:
        raise AssertionError('The actual controlled clock projection must have one probe call site')
    expression = compile(ast.Expression(assignments[0].value), str(SCRIPT), 'eval')
    with patch.object(SMOKE.time, 'time', return_value=wall):
        return eval(expression, dict(vars(SMOKE), first_clock=anchor, day=day))


class G61ClockProjectionTest(unittest.TestCase):
    def test_second_process_clock_accepts_actual_later_prerequisite_finish_history(self):
        # First failed lane: persisted 1010201/1010403 finishTime=1791743764, old clock=1791743747.
        actual = actual_probe_clock(1791743747, 1, 1791743764.4)
        self.assertGreaterEqual(actual, 1791743764,
                                'The actual source acceptance rejects prerequisite finishTime > clock')

    def test_later_same_day_reconnect_does_not_backdate_new_original_history(self):
        actual = actual_probe_clock(1791743747, 1, 1791743881.9)
        self.assertGreaterEqual(actual, 1791743881)
        self.assertEqual((actual + 14400) // 86400, (1791743747 + 14400) // 86400)

    def test_second_day_keeps_exact_offset_from_fresh_wall_projection(self):
        actual = actual_probe_clock(1791743747, 2, 1791743947.5)
        self.assertGreaterEqual(actual - 86400, 1791743947)
        self.assertEqual((actual + 14400) // 86400, (1791743747 + 14400) // 86400 + 1)

    def test_wall_clock_rollback_does_not_move_before_the_original_controlled_anchor(self):
        self.assertEqual(actual_probe_clock(1791743747, 1, 1791743731.2), 1791743747)
        self.assertEqual(actual_probe_clock(1791743747, 2, 1791743731.2), 1791743747 + 86400)

    def test_natural_day_reset_during_smoke_cannot_silently_relabel_fixture_periods(self):
        with self.assertRaisesRegex(AssertionError, 'wall-clock reset'):
            actual_probe_clock(1791743747, 1, 1791743747 + 86400)


if __name__ == '__main__':
    unittest.main()
