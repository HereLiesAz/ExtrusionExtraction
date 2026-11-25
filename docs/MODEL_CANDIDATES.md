# Complementary neural stages

Candidates are additive, not replacements for the calibrated DA3 reconstruction path. A candidate must have inspectable weights, permitted commercial use, a documented input/output contract, an integrity-pinned download and a proven local execution path for Android/ChromeOS and desktop before integration. Desktop-only Python or ComfyUI cannot be a mandatory dependency.

| Candidate | Potential contribution | Status |
| --- | --- | --- |
| [3D_Model_Multiview_Qwen_i21](https://huggingface.co/zuanfilm/3D_Model_Multiview_Qwen_i21) | Missing reference views and complementary reconstruction | Repository metadata reports Apache-2.0, Qwen-Image-2.1, ComfyUI, image-to-3D and Gaussian-splatting tags. Actual graph, weights, memory footprint, licensing chain and portable inference not verified. |
| [UBUNTIGAMES](https://www.kaggle.com/models/zahidkola/ubuntigames) | Candidate for generated references and textures | Model card and portable inference contract not verified. |
| [blender-5-3-zbrush-2027-3dcg-modeling-suit](https://huggingface.co/hudsonrider/blender-5-3-zbrush-2027-3dcg-modeling-suit) | No demonstrated neural capability | Windows portable x64 software tags; executable contents and provenance unverified. Excluded from integration. |

Generated reference views must be identified as synthetic; confidence and pose agreement must be checked before they influence observed geometry. A generated view is not an independent measurement. No candidate is installed or invoked yet.
