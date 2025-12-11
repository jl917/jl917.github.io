# Fine-tuning 微调

### 소개

파인튜닝은 특정 작업이나 도메인에 높은 적합성을 확보하기 위해, 이미 훈련된 대규모 언어 모델에 특정 데이터셋을 사용하여 추가적인 학습을 수행하는 작업을 말합니다.

### 방법

##### Full Fine-tuning

전체 파인튜닝은 모델의 모든 매개변수를 업데이트하여 모델 전체를 새로운 데이터에 맞춰 재학습하는 방식입니다.
일반적으로 작업과 기존 학습된 모델의 차이가 크거나 모델의 높은 적응성이 필요할 때 사용합니다.
많은 컴퓨팅 자원과 시간이 필요하지만 그만큼 성능 향상이 큽니다.

##### Partial Fine-Tuning / Layer Freezing

모델 전체를 다 학습시키지 않고, 특정 레이어만 학습시키는 방식입니다. 미세 조정 중 특정 레이어는 업데이트하지 못하도록 “얼려” 두는 기술입니다.

Layer Freezing는 “여기 레이어는 학습하지 마라”라고 고정시키는 기술
Partial Fine-Tuning “일부 레이어만 학습시키겠다”라는 전략

##### Parameter-Efficient Fine-Tuning(PEFT)

- Prompt Tuning
- P-Tuning
- Prefix-Tuning
- LoRA
- QLoRA
- Adapter Tuning

##### Supervised Fine-Tuning, SFT

##### Unsupervised Fine-Tuning, UFT

### 종류

##### 지도 학습 기반 파인튜닝(Supervised Fine-Tuning, SFT)

##### 비지도 학습 기반 파인튜닝(Unsupervised Fine-Tuning, UFT)

### 장점

- 일반 프롬프트보다 더 좋은 효과를 얻을수 있다.
- 토큰사용도 줄일수 있다.
- 속도가 빠르다

### 단점

- 인력비용
- 복잡한 기술 역량
- 난이도가 높다

### 튜닝

데이터셋 및 모들을 선택하고 튜닝 진행

##### openapi튜닝 샘플

```json
{
  "messages": [
    { "role": "system", "content": "<放入系统讯息>" },
    { "role": "user", "content": "<放入使用者的问题>" },
    { "role": "assistant", "content": "<放入理想的回答>." }
  ]
}
```

##### 실제 푸로세스

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

### 참고

- https://zhuanlan.zhihu.com/p/1942244181457244840
