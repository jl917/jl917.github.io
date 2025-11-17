# Fine-tuning 微调

### 介绍

Fine-tuning (微调) 是一个可以有效让 ChatGPT 输出符合我们预期的方法。
在机器学习领域当中，微调 (Fine-tuning) 是指在已经训练好的模型基础上，进一步调整，让你模型的输出能够更符合你的预期。透过微调，我们可以不用重新训练一个新的模型，这让我们能够省去训练新模型的高昂成本。

### 使用方法

微调的方式很简单，你只需要准备成对的训练资料。然后喂入 Fine-tuning API 就可以完成了。这边指的成对资料，是输入搭配输出

### 好处

做好微调，能够让我们获得以下的好处： 比起指令 (prompt)，若微调的好，输出的成果会更好。 使用更短的指令来获得理想的输出，这会减少 token 使用，进而降低支出成本，同时加快响应的速度。

### 缺点

- API费用较高
- 人力成本
- 这件事往往不是一次到位，而是来回迭代的。

### 流程

- 准备好训练资料
- 训练微调的模型
- 使用微调后的模型

```json
{
  "messages": [
    { "role": "system", "content": "<放入系统讯息>" },
    { "role": "user", "content": "<放入使用者的问题>" },
    { "role": "assistant", "content": "<放入理想的回答>." }
  ]
}
```

### 微调技术

- 全量微调 (Full Fine-Tuning)
- LoRA (Low-Rank Adaptation of Large Language Models)


### 实际操作

```text
[1] Base Model 다운로드 (HF Hub)
       ↓
[2] Python(HuggingFace)에서 파인튜닝 또는 LoRA
       ↓
[3] GGUF로 변환 (llama.cpp converter)
       ↓
[4] Ollama 모델 작성(modelfile)
       ↓
[5] Transformers.js에서 로드하여 추론
```

```python
from transformers import AutoTokenizer, AutoModelForCausalLM, TrainingArguments, Trainer
from peft import LoraConfig, get_peft_model
from datasets import load_dataset

model_name = "mistral-7b"
tokenizer = AutoTokenizer.from_pretrained(model_name)
model = AutoModelForCausalLM.from_pretrained(model_name)

# LoRA 설정
lora_config = LoraConfig(
    r=16,
    lora_alpha=32,
    target_modules=["q_proj", "v_proj"],
    lora_dropout=0.05,
)

model = get_peft_model(model, lora_config)

dataset = load_dataset("your_dataset")

training_args = TrainingArguments(
    output_dir="./lora-out",
    per_device_train_batch_size=2,
    gradient_accumulation_steps=2,
    learning_rate=2e-4,
    num_train_epochs=3,
    fp16=True,
)

trainer = Trainer(
    model=model,
    args=training_args,
    train_dataset=dataset["train"]
)

trainer.train()
model.save_pretrained("./lora-out")
```