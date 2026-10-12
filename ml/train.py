"""Train the LeafCheck model and export it for the Android app.

    python train.py <data_dir>

Reads <data_dir>/img/{train,val,test}/<crop>__<condition>/*.jpg (made by prepare_data.py).
Writes app/src/main/assets/{model.tflite,labels.json} and ml/metrics.json.
All reported numbers are measured on the exported .tflite file, the exact model the app ships.
"""
import json
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
import tensorflow as tf
import keras
from keras import layers

SIZE = 224
BATCH = 64
ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
OUT = ROOT / "ml"


def files(split_dir, classes):
    paths, labels = [], []
    for i, c in enumerate(classes):
        for p in sorted((split_dir / c).glob("*.jpg")):
            paths.append(str(p))
            labels.append(i)
    return np.array(paths), np.array(labels)


def load(path, label):
    img = tf.io.decode_jpeg(tf.io.read_file(path), channels=3)
    return tf.image.resize(img, (SIZE, SIZE)), label


def dataset(paths, labels, shuffle=False):
    ds = tf.data.Dataset.from_tensor_slices((paths, labels))
    if shuffle:
        ds = ds.shuffle(len(paths), seed=1)
    return ds.map(load, num_parallel_calls=tf.data.AUTOTUNE).batch(BATCH).prefetch(tf.data.AUTOTUNE)


def tflite_predict(model_bytes, paths):
    interp = tf.lite.Interpreter(model_content=model_bytes, num_threads=8)
    interp.allocate_tensors()
    inp, out = interp.get_input_details()[0], interp.get_output_details()[0]
    probs = []
    for p in paths:
        img, _ = load(p, 0)
        interp.set_tensor(inp["index"], img[None].numpy().astype(np.float32))
        interp.invoke()
        probs.append(interp.get_tensor(out["index"])[0])
    return np.array(probs)


def crop_of(label):
    return label.split("__")[0]


def scores(probs, labels, classes, restrict_to_crop=False):
    """top-1 / top-3 accuracy. restrict_to_crop mimics the app when the user picks the plant."""
    probs = probs.copy()
    if restrict_to_crop:
        crops = np.array([crop_of(c) for c in classes])
        for i, y in enumerate(labels):
            probs[i][crops != crops[y]] = 0
    order = np.argsort(-probs, axis=1)
    return float(np.mean(order[:, 0] == labels)), float(np.mean([y in o[:3] for o, y in zip(order, labels)]))


def main():
    data = Path(sys.argv[1])
    classes = sorted(d.name for d in (data / "img" / "train").iterdir())
    train_p, train_y = files(data / "img" / "train", classes)
    val_p, val_y = files(data / "img" / "val", classes)
    test_p, test_y = files(data / "img" / "test", classes)
    print(len(classes), "classes", len(train_p), "train", len(val_p), "val", len(test_p), "test", flush=True)

    # gentle class weights so the small garden sets are not drowned out by PlantVillage
    counts = Counter(train_y.tolist())
    median = np.median(list(counts.values()))
    class_weight = {i: float(np.clip((median / counts[i]) ** 0.5, 0.5, 3.0)) for i in range(len(classes))}

    base = keras.applications.MobileNetV3Large(
        input_shape=(SIZE, SIZE, 3), include_top=False, weights="imagenet", pooling="avg", include_preprocessing=True
    )
    base.trainable = False
    augment = keras.Sequential([
        layers.RandomFlip("horizontal_and_vertical"),
        layers.RandomRotation(0.15),
        layers.RandomZoom((-0.2, 0.1)),
        layers.RandomBrightness(0.2, value_range=(0, 255)),
        layers.RandomContrast(0.2),
    ])
    head = keras.Sequential([layers.Dropout(0.3), layers.Dense(len(classes), activation="softmax")])

    inp = keras.Input((SIZE, SIZE, 3))
    train_model = keras.Model(inp, head(base(augment(inp), training=False)))
    infer_model = keras.Model(inp, head(base(inp, training=False)))

    train_ds, val_ds = dataset(train_p, train_y, shuffle=True), dataset(val_p, val_y)
    history = {}

    def fit(epochs, lr, tag):
        train_model.compile(keras.optimizers.Adam(lr), "sparse_categorical_crossentropy", metrics=["accuracy"])
        t = time.time()
        h = train_model.fit(train_ds, validation_data=val_ds, epochs=epochs, class_weight=class_weight, verbose=2,
                            callbacks=[keras.callbacks.EarlyStopping("val_accuracy", patience=2, restore_best_weights=True)])
        history[tag] = {k: [float(x) for x in v] for k, v in h.history.items()}
        print(tag, "done in", round((time.time() - t) / 60), "min", flush=True)

    # 1) train only the new head, 2) unfreeze the top of the backbone and fine-tune gently
    fit(6, 1e-3, "head")
    base.trainable = True
    for layer in base.layers[:-60]:
        layer.trainable = False
    fit(6, 1e-4, "finetune")

    # export with dynamic-range quantization (int8 weights, float math): ~4x smaller, same accuracy
    converter = tf.lite.TFLiteConverter.from_keras_model(infer_model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    model_bytes = converter.convert()
    ASSETS.mkdir(parents=True, exist_ok=True)
    (ASSETS / "model.tflite").write_bytes(model_bytes)
    (ASSETS / "labels.json").write_text(json.dumps(classes, indent=0))

    # every number below comes from the exported .tflite
    probs = tflite_predict(model_bytes, test_p)
    pred = probs.argmax(1)
    top1, top3 = scores(probs, test_y, classes)
    per_class = {c: float(np.mean(pred[test_y == i] == i)) for i, c in enumerate(classes)}
    per_crop, per_source = defaultdict(list), defaultdict(list)
    for p, y, yhat in zip(test_p, test_y, pred):
        per_crop[crop_of(classes[y])].append(y == yhat)
        per_source[Path(p).name.rsplit("_", 1)[0]].append(y == yhat)

    plantdoc = {}
    pd_dir = data / "plantdoc"
    pd_p, pd_y = files(pd_dir, classes) if pd_dir.exists() else ([], [])
    if len(pd_p):
        pd_probs = tflite_predict(model_bytes, pd_p)
        a1, a3 = scores(pd_probs, pd_y, classes)
        c1, c3 = scores(pd_probs, pd_y, classes, restrict_to_crop=True)
        plantdoc = {"images": int(len(pd_p)), "top1": a1, "top3": a3, "top1_plant_picked": c1, "top3_plant_picked": c3}

    metrics = {
        "classes": len(classes),
        "crops": len({crop_of(c) for c in classes} - {"none"}),
        "train_images": int(len(train_p)), "val_images": int(len(val_p)), "test_images": int(len(test_p)),
        "test_top1": top1, "test_top3": top3,
        "test_top1_plant_picked": scores(probs, test_y, classes, restrict_to_crop=True)[0],
        "model_mb": round(len(model_bytes) / 1e6, 2),
        "per_crop": {k: {"acc": float(np.mean(v)), "n": len(v)} for k, v in sorted(per_crop.items())},
        "per_source": {k: {"acc": float(np.mean(v)), "n": len(v)} for k, v in sorted(per_source.items())},
        "per_class": per_class,
        "plantdoc": plantdoc,
        "history": history,
    }
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "metrics.json").write_text(json.dumps(metrics, indent=1))
    print(json.dumps({k: v for k, v in metrics.items() if k not in ("per_class", "history")}, indent=1))


if __name__ == "__main__":
    main()
