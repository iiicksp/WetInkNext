import re

def make_gamma_lut(gamma, steps=16):
    points = [ (i / (steps - 1))**gamma for i in range(steps) ]
    return f"DynamicsCurve(listOf({', '.join(f'{p:.3f}f' for p in points)}))"


def create_preset(id, name, category, render_mode, base_radius, opacity, flow, spacing, spacing_uses_diameter, pressure_size, pressure_opacity, pressure_gamma, hardness, falloff, smoothing, streamline, min_size_ratio, tilt_to_size=0.0, grain_asset="", grain_scale=1.0, shape_asset="", blend_policy="NORMAL_BUILDUP", emission_time="false", auto_close_loop="false", scatter=0.0, rotation_jitter=0.0, size_jitter=0.0, opacity_jitter=0.0, velocity_size=0.0, velocity_opacity=0.0):
    return f"""
    val {id} = BrushPreset(
        id = "{id}",
        settings = BrushSettings(
            name = "{name}",
            category = "{category}",
            renderMode = BrushRenderMode.{render_mode},
            baseRadiusPx = {base_radius}f,
            opacity = {opacity}f,
            flow = {flow}f,
            spacing = {spacing}f,
            spacingUsesDiameter = {str(spacing_uses_diameter).lower()},
            pressureToSize = {str(pressure_size).lower()},
            pressureToOpacity = {str(pressure_opacity).lower()},
            pressureCurve = {make_gamma_lut(pressure_gamma)},
            hardness = {hardness}f,
            falloff = DabFalloff.{falloff},
            smoothing = {smoothing}f,
            streamline = {streamline}f,
            minSizeRatio = {min_size_ratio}f,
            tiltToSize = {tilt_to_size}f,
            {f'grainAssetPath = "{grain_asset}",' if grain_asset else ''}
            {f'grainCanvasLocked = true,' if grain_asset else ''}
            {f'grainScale = {grain_scale}f,' if grain_asset else ''}
            {f'shapeAssetPath = "{shape_asset}",' if shape_asset else ''}
            emissionUsesTime = {emission_time},
            {f'emissionRateHz = 60f,' if emission_time == 'true' else ''}
            blendPolicy = BlendPolicy.{blend_policy},
            useTempStrokeBuffer = {str(blend_policy != 'NON_BUILDUP').lower()},
            scatter = {scatter}f,
            rotationJitter = {rotation_jitter}f,
            sizeJitter = {size_jitter}f,
            opacityJitter = {opacity_jitter}f,
            velocityToSize = {velocity_size}f,
            velocityToOpacity = {velocity_opacity}f
            {f', ribbon = RibbonSettings(autoCloseLoop = {auto_close_loop})' if render_mode == 'RIBBON' else ''}
        ),
    )
"""

presets = []
presets.append(("hb_pencil", create_preset("hb_pencil", "HB Pencil", "Sketching", "STAMP", 4.0, 0.8, 0.7, 0.05, True, True, True, 1.2, 1.0, "HARD", 0.05, 0.02, 0.3, tilt_to_size=0.8, grain_asset="asset:brush/paper_cold_press.png", grain_scale=4.0, velocity_opacity=0.2, rotation_jitter=1.0)))
presets.append(("charcoal", create_preset("charcoal", "Charcoal Block", "Sketching", "STAMP", 15.0, 0.9, 0.6, 0.05, True, True, True, 1.5, 0.5, "SOFT", 0.02, 0.0, 0.4, tilt_to_size=1.2, shape_asset="asset:brush/charcoal_stick.png", grain_asset="asset:brush/noise_coarse.png", grain_scale=2.0, rotation_jitter=0.05)))
presets.append(("chalk", create_preset("chalk", "Chalk", "Sketching", "STAMP", 12.0, 0.8, 0.7, 0.08, True, True, True, 1.3, 0.2, "SOFT", 0.05, 0.0, 0.2, grain_asset="asset:brush/noise_fine.png", grain_scale=1.5, rotation_jitter=1.0)))

presets.append(("technical_pen", create_preset("technical_pen", "Technical Pen", "Inking", "RIBBON", 3.0, 1.0, 1.0, 0.05, True, False, False, 1.0, 1.0, "HARD", 0.3, 0.1, 1.0, blend_policy="NON_BUILDUP", auto_close_loop="false")))
presets.append(("studio_pen", create_preset("studio_pen", "Studio Pen", "Inking", "RIBBON", 6.0, 1.0, 1.0, 0.05, True, True, False, 1.5, 1.0, "HARD", 0.4, 0.2, 0.1, blend_policy="NON_BUILDUP", auto_close_loop="false")))
presets.append(("ink_bleed", create_preset("ink_bleed", "Bleeding Ink", "Inking", "STAMP", 10.0, 1.0, 1.0, 0.05, True, True, False, 1.8, 0.5, "SOFT", 0.2, 0.1, 0.1, grain_asset="asset:brush/paper_hot_press.png", grain_scale=2.0, size_jitter=0.1, rotation_jitter=1.0)))

presets.append(("acrylic", create_preset("acrylic", "Acrylic Flat", "Painting", "STAMP", 20.0, 1.0, 0.9, 0.05, True, True, False, 1.5, 0.8, "SOFT", 0.1, 0.05, 0.5, shape_asset="asset:brush/flat_brush.png", grain_asset="asset:brush/canvas_linen.png", grain_scale=3.0, rotation_jitter=0.02)))
presets.append(("oil_filbert", create_preset("oil_filbert", "Oil Filbert", "Painting", "STAMP", 25.0, 0.9, 0.8, 0.03, True, True, True, 1.2, 0.7, "SOFT", 0.1, 0.05, 0.4, shape_asset="asset:brush/filbert.png", grain_asset="asset:brush/canvas_linen.png", grain_scale=3.0, rotation_jitter=0.05)))
presets.append(("dry_brush", create_preset("dry_brush", "Dry Brush", "Painting", "STAMP", 30.0, 0.8, 0.5, 0.05, True, True, True, 1.5, 0.3, "SOFT", 0.1, 0.0, 0.3, shape_asset="asset:brush/dry_brush.png", rotation_jitter=1.0)))
presets.append(("round_soft", create_preset("round_soft", "Soft Round", "Painting", "STAMP", 30.0, 1.0, 0.8, 0.05, True, True, True, 1.2, 0.0, "AIRBRUSH", 0.1, 0.05, 0.2, shape_asset="asset:brush/round_soft.png", rotation_jitter=1.0)))
presets.append(("water_color", create_preset("water_color", "Watercolor Soft", "Painting", "STAMP", 40.0, 0.7, 0.5, 0.04, True, True, True, 1.1, 0.1, "SOFT", 0.2, 0.05, 0.3, grain_asset="asset:brush/paper_cold_press.png", grain_scale=3.0, blend_policy="MULTIPLY", rotation_jitter=1.0)))

presets.append(("soft_airbrush", create_preset("soft_airbrush", "Soft Airbrush", "Airbrush", "STAMP", 100.0, 0.1, 0.05, 0.03, True, False, True, 1.1, 0.0, "AIRBRUSH", 0.1, 0.0, 1.0, emission_time='true', rotation_jitter=1.0)))
presets.append(("hard_airbrush", create_preset("hard_airbrush", "Hard Airbrush", "Airbrush", "STAMP", 80.0, 0.3, 0.2, 0.05, True, True, True, 1.2, 0.5, "SOFT", 0.1, 0.0, 0.2, rotation_jitter=1.0)))

presets.append(("marker", create_preset("marker", "Broad Marker", "Markers", "STAMP", 15.0, 0.7, 1.0, 0.04, True, False, False, 1.0, 1.0, "HARD", 0.1, 0.05, 1.0, blend_policy="MULTIPLY", shape_asset="asset:brush/flat_brush.png", rotation_jitter=0.0)))
presets.append(("fine_liner", create_preset("fine_liner", "Fine Liner", "Markers", "RIBBON", 2.0, 1.0, 1.0, 0.05, True, False, False, 1.0, 1.0, "HARD", 0.2, 0.1, 1.0, blend_policy="NON_BUILDUP", auto_close_loop="false")))

presets.append(("sponge", create_preset("sponge", "Sponge", "Textures", "STAMP", 60.0, 0.8, 0.6, 0.3, True, True, True, 1.5, 0.2, "SOFT", 0.0, 0.0, 0.5, shape_asset="asset:brush/sponge.png", rotation_jitter=1.0, size_jitter=0.2, opacity_jitter=0.2, scatter=0.2)))
presets.append(("splatter", create_preset("splatter", "Splatter", "Textures", "STAMP", 80.0, 1.0, 1.0, 0.6, True, True, True, 1.0, 0.5, "SOFT", 0.0, 0.0, 0.1, shape_asset="asset:brush/splatter.png", rotation_jitter=1.0, size_jitter=0.5, opacity_jitter=0.2, scatter=0.5)))
presets.append(("cracks", create_preset("cracks", "Cracks", "Textures", "STAMP", 50.0, 1.0, 1.0, 0.8, True, True, False, 1.0, 0.5, "SOFT", 0.0, 0.0, 0.3, shape_asset="asset:brush/crack.png", rotation_jitter=1.0, size_jitter=0.3, scatter=0.1)))
presets.append(("leaf", create_preset("leaf", "Scattering Leaves", "Textures", "STAMP", 40.0, 1.0, 1.0, 1.5, True, True, False, 1.0, 1.0, "HARD", 0.0, 0.0, 0.4, shape_asset="asset:brush/leaf.png", rotation_jitter=1.0, size_jitter=0.4, scatter=1.0)))
presets.append(("fiber", create_preset("fiber", "Fibers", "Textures", "STAMP", 40.0, 0.8, 0.8, 0.2, True, True, True, 1.0, 0.3, "SOFT", 0.0, 0.0, 0.2, shape_asset="asset:brush/fiber.png", rotation_jitter=1.0, size_jitter=0.1, opacity_jitter=0.1, scatter=0.15)))

code = "\n".join([p[1] for p in presets])

categories_code = f"""
    val allCategories = listOf(
        BrushUiCategory("Sketching", androidx.compose.material.icons.Icons.Default.Create, listOf({', '.join([p[0] for p in presets if "Sketching" in p[1]])})),
        BrushUiCategory("Inking", androidx.compose.material.icons.Icons.Default.Create, listOf({', '.join([p[0] for p in presets if "Inking" in p[1]])})),
        BrushUiCategory("Painting", androidx.compose.material.icons.Icons.Default.Brush, listOf({', '.join([p[0] for p in presets if "Painting" in p[1]])})),
        BrushUiCategory("Airbrush", androidx.compose.material.icons.Icons.Default.Brush, listOf({', '.join([p[0] for p in presets if "Airbrush" in p[1]])})),
        BrushUiCategory("Markers", androidx.compose.material.icons.Icons.Default.Create, listOf({', '.join([p[0] for p in presets if "Markers" in p[1]])})),
        BrushUiCategory("Textures", androidx.compose.material.icons.Icons.Default.Star, listOf({', '.join([p[0] for p in presets if "Textures" in p[1]])})),
    )
"""

with open(r"K:\dev\WetInk-Next\app\src\main\java\com\wetinknext\engine\brush\BrushLibrary.kt", "r", encoding="utf-8") as f:
    orig = f.read()

import re
head = orig[:orig.find("object BrushLibrary {") + len("object BrushLibrary {")]

new_content = head + "\n" + code + "\n" + categories_code + "\n}\n"

with open(r"K:\dev\WetInk-Next\app\src\main\java\com\wetinknext\engine\brush\BrushLibrary.kt", "w", encoding="utf-8") as f:
    f.write(new_content)

print("Updated BrushLibrary.kt")
