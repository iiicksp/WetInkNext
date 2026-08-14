import os
import numpy as np
from PIL import Image, ImageFilter, ImageDraw

out_dir = r"K:\dev\WetInk-Next\app\src\main\assets\brush"
os.makedirs(out_dir, exist_ok=True)

def save(img, name):
    img = img.convert("L")
    img.save(os.path.join(out_dir, name + ".png"))

def generate_noise(size, scale=1.0):
    noise = np.random.rand(int(size/scale), int(size/scale)) * 255
    img = Image.fromarray(noise.astype('uint8'))
    if scale > 1.0:
        img = img.resize((size, size), Image.Resampling.BILINEAR)
    return img

# 1. noise_fine
save(generate_noise(512, 1.0), "noise_fine")
# 2. noise_coarse
save(generate_noise(512, 4.0).filter(ImageFilter.GaussianBlur(1.0)), "noise_coarse")

# 3. paper_cold_press
noise1 = np.random.rand(256, 256) * 255
img1 = Image.fromarray(noise1.astype('uint8')).resize((512, 512), Image.Resampling.BICUBIC)
img1 = img1.filter(ImageFilter.GaussianBlur(3.0))
noise2 = np.random.rand(512, 512) * 50
arr = np.clip(np.array(img1) + noise2 - 25, 0, 255)
save(Image.fromarray(arr.astype('uint8')), "paper_cold_press")

# 4. canvas_linen
arr = np.ones((512, 512)) * 128
for i in range(0, 512, 4):
    arr[i:i+2, :] += 40
    arr[:, i:i+2] += 40
arr += np.random.rand(512, 512) * 40 - 20
save(Image.fromarray(np.clip(arr, 0, 255).astype('uint8')), "canvas_linen")

# 5. round_soft
img = Image.new("L", (256, 256), 0)
draw = ImageDraw.Draw(img)
draw.ellipse((10, 10, 246, 246), fill=255)
img = img.filter(ImageFilter.GaussianBlur(20))
save(img, "round_soft")

# 6. flat_brush
img = Image.new("L", (256, 256), 0)
draw = ImageDraw.Draw(img)
for i in range(40, 216, 8):
    draw.line((i, 20, i, 236), fill=np.random.randint(150, 255), width=np.random.randint(2, 6))
img = img.filter(ImageFilter.GaussianBlur(2))
save(img, "flat_brush")

# 7. filbert
img = Image.new("L", (256, 256), 0)
draw = ImageDraw.Draw(img)
draw.pieslice((30, 30, 226, 226), 180, 0, fill=255)
draw.rectangle((30, 128, 226, 226), fill=255)
for i in range(30, 226, 6):
    draw.line((i, 30, i, 226), fill=np.random.randint(100, 255), width=2)
img = img.filter(ImageFilter.GaussianBlur(4))
save(img, "filbert")

# 8. charcoal_stick
img = Image.new("L", (256, 256), 0)
draw = ImageDraw.Draw(img)
draw.rectangle((40, 40, 216, 216), fill=200)
noise = np.random.rand(256, 256) * 100 - 50
arr = np.clip(np.array(img) + noise, 0, 255).astype('uint8')
img = Image.fromarray(arr).filter(ImageFilter.GaussianBlur(1))
save(img, "charcoal_stick")

# 9. sponge
noise = np.random.rand(64, 64) * 255
img = Image.fromarray(noise.astype('uint8')).resize((256, 256), Image.Resampling.BICUBIC)
img = img.filter(ImageFilter.GaussianBlur(2))
arr = np.array(img)
arr[arr < 140] = 0
arr[arr >= 140] = (arr[arr >= 140] - 140) * 2.2
mask = Image.new("L", (256, 256), 0)
ImageDraw.Draw(mask).ellipse((10, 10, 246, 246), fill=255)
mask = mask.filter(ImageFilter.GaussianBlur(10))
arr = arr * (np.array(mask) / 255.0)
save(Image.fromarray(np.clip(arr, 0, 255).astype('uint8')), "sponge")

# 10. splatter
img = Image.new("L", (256, 256), 0)
draw = ImageDraw.Draw(img)
for _ in range(80):
    x, y = np.random.normal(128, 40, 2)
    r = np.random.exponential(3)
    draw.ellipse((x-r, y-r, x+r, y+r), fill=255)
img = img.filter(ImageFilter.GaussianBlur(1))
save(img, "splatter")

# 11. fiber
img = Image.new("L", (256, 256), 0)
draw = ImageDraw.Draw(img)
for _ in range(200):
    y = np.random.randint(0, 256)
    draw.line((0, y, 256, y), fill=np.random.randint(50, 150), width=1)
img = img.filter(ImageFilter.GaussianBlur(1))
save(img, "fiber")

print("Done generating 11 textures!")
