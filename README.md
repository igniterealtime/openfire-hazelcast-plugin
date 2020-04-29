# About this fork

This fork is designed to provide support to the clustering plugin for ECS and Fargate tasks.
It uses a separate component - [hazelcast-aws-ecs](https://github.com/iKentoo/hazelcast-aws-ecs) for the discovery.

It also reverts to an older revision of the plugin (little before 2.4.2) because iKentoo's component needs an older version of Hazelcast (3.11)
