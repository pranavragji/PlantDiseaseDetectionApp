"""Download the source datasets from Hugging Face and turn them into one folder of
256px JPEGs, split into train/val/test.

    python prepare_data.py <data_dir>

Output: <data_dir>/img/<split>/<crop>__<condition>/<n>.jpg plus <data_dir>/plantdoc/
(a separate real-world photo set used only for evaluation).
"""
import hashlib
import io
import json
import sys
from pathlib import Path

import pyarrow.parquet as pq
from huggingface_hub import HfApi, hf_hub_download
from PIL import Image

SIZE = 256
CAP = 1000  # max images per class, keeps PlantVillage from drowning the small sets

# dataset -> {source label: "crop__condition"}; labels left out are skipped
SOURCES = {
    "Project-AgML/plant_village_classification": {
        "Apple___Apple_scab": "apple__scab",
        "Apple___Black_rot": "apple__black_rot",
        "Apple___Cedar_apple_rust": "apple__cedar_rust",
        "Apple___healthy": "apple__healthy",
        "Background_without_leaves": "none__no_leaf",
        "Blueberry___healthy": "blueberry__healthy",
        "Cherry___Powdery_mildew": "cherry__powdery_mildew",
        "Cherry___healthy": "cherry__healthy",
        "Corn___Cercospora_leaf_spot Gray_leaf_spot": "corn__gray_leaf_spot",
        "Corn___Common_rust": "corn__rust",
        "Corn___Northern_Leaf_Blight": "corn__leaf_blight",
        "Corn___healthy": "corn__healthy",
        "Grape___Black_rot": "grape__black_rot",
        "Grape___Esca_(Black_Measles)": "grape__esca",
        "Grape___Leaf_blight_(Isariopsis_Leaf_Spot)": "grape__leaf_spot",
        "Grape___healthy": "grape__healthy",
        "Orange___Haunglongbing_(Citrus_greening)": "orange__citrus_greening",
        "Peach___Bacterial_spot": "peach__bacterial_spot",
        "Peach___healthy": "peach__healthy",
        "Pepper,_bell___Bacterial_spot": "bell_pepper__bacterial_spot",
        "Pepper,_bell___healthy": "bell_pepper__healthy",
        "Potato___Early_blight": "potato__early_blight",
        "Potato___Late_blight": "potato__late_blight",
        "Potato___healthy": "potato__healthy",
        "Raspberry___healthy": "raspberry__healthy",
        "Soybean___healthy": "soybean__healthy",
        "Squash___Powdery_mildew": "squash__powdery_mildew",
        "Strawberry___Leaf_scorch": "strawberry__leaf_scorch",
        "Strawberry___healthy": "strawberry__healthy",
        "Tomato___Bacterial_spot": "tomato__bacterial_spot",
        "Tomato___Early_blight": "tomato__early_blight",
        "Tomato___Late_blight": "tomato__late_blight",
        "Tomato___Leaf_Mold": "tomato__leaf_mold",
        "Tomato___Septoria_leaf_spot": "tomato__septoria_leaf_spot",
        "Tomato___Spider_mites Two-spotted_spider_mite": "tomato__spider_mites",
        "Tomato___Target_Spot": "tomato__target_spot",
        "Tomato___Tomato_Yellow_Leaf_Curl_Virus": "tomato__yellow_leaf_curl",
        "Tomato___Tomato_mosaic_virus": "tomato__mosaic_virus",
        "Tomato___healthy": "tomato__healthy",
    },
    "Project-AgML/COLD_chili_leaf_disease_classification": {
        "cercospora": "chili__cercospora_leaf_spot",
        "healthy": "chili__healthy",
        "mites_and_trips": "chili__mites_thrips",
        "nutritional": "chili__nutrient_deficiency",
        "powdery mildew": "chili__powdery_mildew",
    },
    "Project-AgML/COLD_onion_leaf_disease_classification": {
        "Iris yellow virus": "onion__iris_yellow_spot",
        "Stemphylium leaf blight and collectrichum leaf blight": "onion__leaf_blight",
        "healthy": "onion__healthy",
        "purple blotch": "onion__purple_blotch",
    },
    "Project-AgML/eggplant_disease_classification": {
        "Healthy Leaf": "eggplant__healthy",
        "Insect Pest Disease": "eggplant__insect_damage",
        "Leaf Spot Disease": "eggplant__leaf_spot",
        "Mosaic Virus Disease": "eggplant__mosaic_virus",
        "White Mold Disease": "eggplant__white_mold",
        "Wilt Disease": "eggplant__wilt",
    },
    "Project-AgML/money_plant_disease_classification": {
        "Bacterial wilt disease": "money_plant__bacterial_wilt",
        "Healthy": "money_plant__healthy",
        "Manganese Toxicity": "money_plant__manganese_toxicity",
    },
    "Project-AgML/BananaLSD_leaf_disease_classification": {
        "cordana": "banana__cordana_leaf_spot",
        "healthy": "banana__healthy",
        "pestalotiopsis": "banana__pestalotiopsis",
        "sigatoka": "banana__sigatoka",
    },
    "AI-Lab-Makerere/beans": {
        "angular_leaf_spot": "bean__angular_leaf_spot",
        "bean_rust": "bean__rust",
        "healthy": "bean__healthy",
    },
    "Project-AgML/papaya_leaf_disease_classification_bangladesh": {
        "Healthy Leaf": "papaya__healthy",
        "Leaf Curl": "papaya__leaf_curl",
        "Mealybug": "papaya__mealybugs",
        "Mite Disease": "papaya__spider_mites",
        "Mosaic": "papaya__mosaic_virus",
        "Ring Spot": "papaya__ringspot_virus",
    },
    "Project-AgML/IDDMSLD_spinach_leaf_disease_classification": {
        "Anthracnose": "spinach__anthracnose",
        "Bacterial-Spot": "spinach__bacterial_spot",
        "Downy-Mildew": "spinach__downy_mildew",
        "Healthy-Leaf": "spinach__healthy",
        "Pest-Damage": "spinach__insect_damage",
    },
    "Project-AgML/grape_leaf_disease_classification": {
        "Bacterial Leaf Spot": "grape__bacterial_spot",
        "Downy Mildew": "grape__downy_mildew",
        "Healthy Leaves": "grape__healthy",
        "Powdery Mildew": "grape__powdery_mildew",
    },
    "Project-AgML/plum_leaf_fruit_disease_classification": {
        "Bacterial Spot": "plum__bacterial_spot",
        "Healthy Leaf": "plum__healthy",
        "Shot Hole": "plum__shot_hole",
        "Wilted Leaf": "plum__wilt",
    },
    "Project-AgML/apple_leaf_disease_classification": {
        "Alternaria": "apple__alternaria_leaf_spot",
        "Apple_Mosaic": "apple__mosaic_virus",
        "Healthy": "apple__healthy",
    },
    "Project-AgML/turmeric_leaf_disease_classification": {
        "Aphids_Disease": "turmeric__aphids",
        "Blotch": "turmeric__leaf_blotch",
        "Healthy_Leaf": "turmeric__healthy",
        "Leaf_Spot": "turmeric__leaf_spot",
    },
    "Project-AgML/roseleafset_disease_classification": {
        "Black Spot": "rose__black_spot",
        "Dry Leaf": "rose__dry_leaf",
        "Healthy Leaf": "rose__healthy",
        "Leaf Holes": "rose__insect_damage",
    },
}

# PlantDoc: real-world photos, used only to measure how the model does outside the lab
PLANTDOC = {
    "Apple Scab Leaf": "apple__scab",
    "Apple leaf": "apple__healthy",
    "Apple rust leaf": "apple__cedar_rust",
    "Bell_pepper leaf": "bell_pepper__healthy",
    "Bell_pepper leaf spot": "bell_pepper__bacterial_spot",
    "Blueberry leaf": "blueberry__healthy",
    "Cherry leaf": "cherry__healthy",
    "Corn Gray leaf spot": "corn__gray_leaf_spot",
    "Corn leaf blight": "corn__leaf_blight",
    "Corn rust leaf": "corn__rust",
    "Peach leaf": "peach__healthy",
    "Potato leaf early blight": "potato__early_blight",
    "Potato leaf late blight": "potato__late_blight",
    "Raspberry leaf": "raspberry__healthy",
    "Soyabean leaf": "soybean__healthy",
    "Squash Powdery mildew leaf": "squash__powdery_mildew",
    "Strawberry leaf": "strawberry__healthy",
    "Tomato Early blight leaf": "tomato__early_blight",
    "Tomato Septoria leaf spot": "tomato__septoria_leaf_spot",
    "Tomato leaf": "tomato__healthy",
    "Tomato leaf bacterial spot": "tomato__bacterial_spot",
    "Tomato leaf late blight": "tomato__late_blight",
    "Tomato leaf mosaic virus": "tomato__mosaic_virus",
    "Tomato leaf yellow virus": "tomato__yellow_leaf_curl",
    "Tomato mold leaf": "tomato__leaf_mold",
    "Tomato two spotted spider mites leaf": "tomato__spider_mites",
    "grape leaf": "grape__healthy",
    "grape leaf black rot": "grape__black_rot",
}


def class_names(api, repo):
    info = api.dataset_info(repo).card_data["dataset_info"]
    info = info[0] if isinstance(info, list) else info
    for f in info["features"]:
        if isinstance(f["dtype"], dict) and "class_label" in f["dtype"]:
            names = f["dtype"]["class_label"]["names"]
            return f["name"], [names[str(i)] if isinstance(names, dict) else names[i] for i in range(len(names))]
    raise ValueError(repo)


def parquet_files(api, repo):
    files = [s.rfilename for s in api.dataset_info(repo).siblings if s.rfilename.endswith(".parquet")]
    raw = [f for f in files if f.startswith("raw/")]
    return raw or [f for f in files if not f.startswith("augmented/")]


def square(img_bytes):
    img = Image.open(io.BytesIO(img_bytes)).convert("RGB")
    w, h = img.size
    s = min(w, h)
    img = img.crop(((w - s) // 2, (h - s) // 2, (w - s) // 2 + s, (h - s) // 2 + s))
    return img.resize((SIZE, SIZE), Image.BILINEAR)


def bucket(digest):
    # stable split from the image hash, so reruns always put an image in the same place
    return int(hashlib.md5(digest.encode()).hexdigest(), 16) % 10


def lab_split(digest):  # 80/10/10
    b = bucket(digest)
    return "img/test" if b == 0 else "img/val" if b == 1 else "img/train"


def real_split(digest):
    # PlantDoc garden photos: 40% kept aside as the real-world test, 10% val, 50% train.
    # Without them the model learns photo style (plain vs busy background) instead of the leaf.
    b = bucket(digest)
    return "plantdoc" if b < 4 else "img/val" if b == 4 else "img/train"


def export(api, repo, mapping, data, cap, split, train_copies=1):
    label_col, names = class_names(api, repo)
    counts = {}
    for f in parquet_files(api, repo):
        path = hf_hub_download(repo, f, repo_type="dataset", cache_dir=str(data / "hf"))
        table = pq.read_table(path, columns=["image", label_col])
        for img, lab in zip(table.column("image").to_pylist(), table.column(label_col).to_pylist()):
            cls = mapping.get(names[lab])
            if cls is None or counts.get(cls, 0) >= cap:
                continue
            digest = hashlib.md5(img["bytes"]).hexdigest()
            where = split(digest)
            d = data / where / cls
            d.mkdir(parents=True, exist_ok=True)
            # small real-world sets are repeated in train so they are not drowned out (augmentation varies each copy)
            copies = train_copies if where == "img/train" else 1
            targets = [d / f"{repo.split('/')[1][:12]}_{digest[:12]}{'' if k == 0 else f'r{k}'}.jpg" for k in range(copies)]
            try:
                if not all(t.exists() for t in targets):
                    sq = square(img["bytes"])
                    for t in targets:
                        sq.save(t, quality=90)
            except Exception as e:  # a few source files are truncated; skip them
                print("skip", repo, e)
                continue
            counts[cls] = counts.get(cls, 0) + 1
    print(repo, sum(counts.values()), "images,", len(counts), "classes", flush=True)
    return counts


def main():
    data = Path(sys.argv[1])
    api = HfApi()
    totals = {}
    for repo, mapping in SOURCES.items():
        for cls, n in export(api, repo, mapping, data, CAP, lab_split).items():
            totals[cls] = totals.get(cls, 0) + n
    export(api, "Project-AgML/plant_doc_classification", PLANTDOC, data, 10**9, real_split, train_copies=3)
    (data / "counts.json").write_text(json.dumps(dict(sorted(totals.items())), indent=1))
    print(len(totals), "classes,", sum(totals.values()), "images")


if __name__ == "__main__":
    main()
