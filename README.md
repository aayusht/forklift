forklift
========

Android library to simplify Loaders

Forklift provides support classes designed to make working with Android's Loader framework easier. Stemming from a need for a more powerful AsyncTaskLoader, while at the same time maintaining its simplicity, **AsyncChainLoader** enables the developer to chain multiple loaders (of any ancestry!) together to return multiple results, potentially from different sources, while appearing to the Loader's consumer to be all coming from the same instance. For cases where functionality that's built into a Loader must be run synchronously, the project's namesake class **Forklift** can capture a Loader and block while processing it for a result.
