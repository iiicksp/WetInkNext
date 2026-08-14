import os
import numpy as np
from PIL import Image, ImageFilter, ImageDraw

out_dir = r"K:\dev\WetInk-Next\app\src\main\assets\brush"
os.makedirs(out_dir, exist_ok=True)

def save(img, name):
    img = img.convert("L")
    img.save(os.path.join(out_dir, name + ".png"))

# 12. dry_brush
img = Image.new("L", (256, 256), 0)
draw = ImageDraw.Draw(img)
for i in range(50, 206, 12):
    if np.random.rand() > 0.3:
        draw.line((i, 20, i, 236), fill=np.random.randint(150, 255), width=np.random.randint(1, 4))
img = img.filter(ImageFilter.GaussianBlur(1))
save(img, "dry_brush")

# 13. leaf
img = Image.new("L", (256, 256), 0)
draw = ImageDraw.Draw(img)
draw.polygon([(128, 20), (200, 100), (128, 236), (56, 100)], fill=255)
img = img.filter(ImageFilter.GaussianBlur(3))
save(img, "leaf")

# 14. crack
img = Image.new("L", (512, 512), 255)
draw = ImageDraw.Draw(img)
x, y = 256, 20
for _ in range(20):
    nx = x + np.random.randint(-40, 40)
    ny = y + np.random.randint(10, 40)
    draw.line((x, y, nx, ny), fill=0, width=np.random.randint(2, 6))
    if np.random.rand() > 0.7:
        bx, by = x, y
        for _ in range(5):
            nbx = bx + np.random.randint(-30, 30)
            nby = by + np.random.randint(-10, 30)
            draw.line((bx, by, nbx, nby), fill=0, width=np.random.randint(1, 3))
            bx, by = nbx, nby
    x, y = nx, ny
img = img.filter(ImageFilter.GaussianBlur(1))
save(img, "crack")

# 15. paper_hot_press
noise = np.random.rand(512, 512) * 20
arr = np.ones((512, 512)) * 230 - noise
img = Image.fromarray(arr.astype('uint8')).filter(ImageFilter.GaussianBlur(1.0))
save(img, "paper_hot_press")

print("Done generating 4 more textures!")
