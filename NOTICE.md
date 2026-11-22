# Сторонние компоненты

GigaAM Dictation — MIT-проект, собранный из открытых компонентов. Ниже — что именно используется и под какой лицензией.

## Код и дизайн

| Компонент | Источник | Лицензия |
| --- | --- | --- |
| Hex — движок диктовки, текст-шаги, звуки, оформление | https://github.com/anomalyco/hex | MIT, © 2026 Christopher Langton |
| hex-for-android — Android-порт, accessibility-вставка, оверлей, настройки | https://github.com/monkey-w1n5t0n/hex-for-android | MIT |
| transcribe.cpp — инференс GGUF-моделей | https://github.com/anomalyco/transcribe.cpp | MIT |
| GigaAM v3 — модель и веса | https://huggingface.co/ai-sage/GigaAM-v3 | MIT |
| GigaAM v3 e2e RNN-T GGUF Q5_K_M | https://huggingface.co/handy-computer/gigaam-v3-e2e-rnnt-gguf, ревизия `3efd5b39882ceadca5570a8fb9fa1a2c801d6d6d` | MIT |
| Inter | https://rsms.me/inter/ | SIL OFL 1.1 |
| IBM Plex Mono | https://github.com/IBM/plex | SIL OFL 1.1 |

Шрифтовые лицензии лежат в `android/licenses/`.

## Изменения этого проекта

- Модель Parakeet Unified English заменена на GigaAM v3 e2e RNN-T (русский).
- Пузырь заменён на mic-кнопку в полосе подсказок клавиатуры; показ привязан к фокусу в поле и открытой клавиатуре.
- Добавлены форма, размер и горизонтальный сдвиг кнопки.
- Добавлена живая звуковая волна во время записи.
- Английские текстовые шаги отключены по умолчанию; остались пользовательские замены слов.
- Пакет `app.gigaam.dictation`, отдельная подпись и обновлённые тексты.

Исходный код доступен под MIT; см. [LICENSE](LICENSE).
