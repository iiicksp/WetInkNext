def create_preset(id, name, category, render_mode, base_radius, opacity, flow, spacing, spacing_uses_diameter, pressure_size, pressure_opacity, pressure_gamma, hardness, falloff, smoothing, streamline, min_size_ratio, tilt_to_size=0.0, grain_asset="", grain_scale=1.0, shape_asset="", shape_reverse="false", blend_policy="NORMAL_BUILDUP", emission_time="false"):
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
            pressureGamma = {pressure_gamma}f,
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
            useTempStrokeBuffer = {str(blend_policy != 'NON_BUILDUP').lower()}
        ),
    )
"""

print(create_preset("hb_pencil", "HB Pencil", "Sketching", "STAMP", 4.0, 0.8, 0.7, 0.1, True, True, True, 1.2, 0.8, "SOFT", 0.05, 0.02, 0.3, tilt_to_size=0.8, grain_asset="asset:brush/paper_cold_press.png", grain_scale=4.0))
print(create_preset("charcoal", "Charcoal Block", "Sketching", "STAMP", 15.0, 0.9, 0.6, 0.05, True, True, True, 1.5, 0.5, "SOFT", 0.02, 0.0, 0.4, tilt_to_size=1.2, shape_asset="asset:brush/charcoal_stick.png", grain_asset="asset:brush/noise_coarse.png", grain_scale=2.0))
print(create_preset("technical_pen", "Technical Pen", "Inking", "RIBBON", 3.0, 1.0, 1.0, 0.05, True, False, False, 1.0, 1.0, "HARD", 0.3, 0.1, 1.0, blend_policy="NON_BUILDUP"))
print(create_preset("studio_pen", "Studio Pen", "Inking", "RIBBON", 6.0, 1.0, 1.0, 0.05, True, True, False, 1.5, 1.0, "HARD", 0.4, 0.2, 0.1, blend_policy="NON_BUILDUP"))
print(create_preset("marker", "Broad Marker", "Markers", "RIBBON", 12.0, 0.7, 1.0, 0.05, True, False, False, 1.0, 1.0, "HARD", 0.2, 0.1, 1.0, blend_policy="MULTIPLY"))
print(create_preset("soft_airbrush", "Soft Airbrush", "Airbrush", "STAMP", 100.0, 0.1, 0.05, 0.03, True, False, True, 1.1, 0.0, "AIRBRUSH", 0.1, 0.0, 1.0, emission_time='true'))
print(create_preset("hard_airbrush", "Hard Airbrush", "Airbrush", "STAMP", 80.0, 0.3, 0.2, 0.05, True, True, True, 1.2, 0.5, "SOFT", 0.1, 0.0, 0.2))
print(create_preset("acrylic", "Acrylic Flat", "Painting", "STAMP", 20.0, 1.0, 0.9, 0.05, True, True, False, 1.5, 0.8, "SOFT", 0.1, 0.05, 0.5, shape_asset="asset:brush/flat_brush.png", grain_asset="asset:brush/canvas_linen.png", grain_scale=3.0))
print(create_preset("oil_filbert", "Oil Filbert", "Painting", "STAMP", 25.0, 0.9, 0.8, 0.03, True, True, True, 1.2, 0.7, "SOFT", 0.1, 0.05, 0.4, shape_asset="asset:brush/filbert.png", grain_asset="asset:brush/canvas_linen.png", grain_scale=3.0))
print(create_preset("dry_brush", "Dry Brush", "Painting", "STAMP", 30.0, 0.8, 0.5, 0.05, True, True, True, 1.5, 0.3, "SOFT", 0.1, 0.0, 0.3, shape_asset="asset:brush/dry_brush.png"))
print(create_preset("sponge", "Sponge", "Textures", "STAMP", 60.0, 0.8, 0.6, 0.3, True, True, True, 1.5, 0.2, "SOFT", 0.0, 0.0, 0.5, shape_asset="asset:brush/sponge.png"))
print(create_preset("splatter", "Splatter", "Textures", "STAMP", 80.0, 1.0, 1.0, 0.6, True, True, True, 1.0, 0.5, "SOFT", 0.0, 0.0, 0.1, shape_asset="asset:brush/splatter.png"))
print(create_preset("cracks", "Cracks", "Textures", "STAMP", 50.0, 1.0, 1.0, 0.8, True, True, False, 1.0, 0.5, "SOFT", 0.0, 0.0, 0.3, shape_asset="asset:brush/crack.png"))
print(create_preset("leaf", "Scattering Leaves", "Textures", "STAMP", 40.0, 1.0, 1.0, 1.5, True, True, False, 1.0, 1.0, "HARD", 0.0, 0.0, 0.4, shape_asset="asset:brush/leaf.png"))
print(create_preset("fiber", "Fibers", "Textures", "STAMP", 40.0, 0.8, 0.8, 0.2, True, True, True, 1.0, 0.3, "SOFT", 0.0, 0.0, 0.2, shape_asset="asset:brush/fiber.png"))
print(create_preset("ink_bleed", "Bleeding Ink", "Inking", "STAMP", 10.0, 1.0, 1.0, 0.05, True, True, False, 1.8, 0.5, "SOFT", 0.2, 0.1, 0.1, grain_asset="asset:brush/paper_hot_press.png", grain_scale=2.0))
print(create_preset("chalk", "Chalk", "Sketching", "STAMP", 12.0, 0.8, 0.7, 0.08, True, True, True, 1.3, 0.2, "SOFT", 0.05, 0.0, 0.2, grain_asset="asset:brush/noise_fine.png", grain_scale=1.5))
print(create_preset("round_soft", "Soft Round", "Painting", "STAMP", 30.0, 1.0, 0.8, 0.05, True, True, True, 1.2, 0.0, "AIRBRUSH", 0.1, 0.05, 0.2, shape_asset="asset:brush/round_soft.png"))
print(create_preset("water_color", "Watercolor Soft", "Painting", "STAMP", 40.0, 0.7, 0.5, 0.04, True, True, True, 1.1, 0.1, "SOFT", 0.2, 0.05, 0.3, grain_asset="asset:brush/paper_cold_press.png", grain_scale=3.0, blend_policy="MULTIPLY"))
print(create_preset("fine_liner", "Fine Liner", "Markers", "RIBBON", 2.0, 1.0, 1.0, 0.05, True, False, False, 1.0, 1.0, "HARD", 0.2, 0.1, 1.0, blend_policy="NON_BUILDUP"))

