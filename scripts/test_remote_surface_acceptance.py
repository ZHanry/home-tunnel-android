import importlib.util
from pathlib import Path
import unittest


script = Path(__file__).with_name("run-remote-surface-acceptance.py")
specification = importlib.util.spec_from_file_location("remote_surface_acceptance", script)
acceptance = importlib.util.module_from_spec(specification)
specification.loader.exec_module(acceptance)


class DecodedSurfacePolicyTest(unittest.TestCase):
    def test_adaptive_downscale_preserves_usable_video(self):
        fixture = {"width": 2560, "height": 1440}
        evidence = {"source_width": 2560, "source_height": 1440, "width": 1920, "height": 1080}
        self.assertTrue(acceptance.valid_decoded_size(fixture, evidence))
        self.assertTrue(acceptance.valid_decoded_size(fixture, {**evidence, "width": 2560, "height": 1440}))

    def test_small_distorted_or_misbound_frames_are_rejected(self):
        fixture = {"width": 2560, "height": 1440}
        evidence = {"source_width": 2560, "source_height": 1440, "width": 1920, "height": 1080}
        for change in ({"width": 640, "height": 360}, {"width": 1920, "height": 900},
                       {"width": 3000}, {"source_width": 1920}, {"height": None}):
            self.assertFalse(acceptance.valid_decoded_size(fixture, {**evidence, **change}))


if __name__ == "__main__":
    unittest.main()
